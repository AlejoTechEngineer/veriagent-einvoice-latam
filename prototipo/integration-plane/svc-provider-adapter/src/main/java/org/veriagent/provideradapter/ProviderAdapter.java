package org.veriagent.provideradapter;

import io.github.resilience4j.retry.Retry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.veriagent.contracts.events.EventEnvelope;
import org.veriagent.contracts.events.Payloads;
import org.veriagent.contracts.events.Payloads.Channel;
import org.veriagent.contracts.events.Payloads.ErrorCategory;
import org.veriagent.contracts.events.Payloads.FinalStatus;
import org.veriagent.contracts.events.Topics;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.bus.Events;
import org.veriagent.core.bus.FlowEvents;
import org.veriagent.core.correlation.CorrelationContext;
import org.veriagent.core.ledger.InvoiceLedger;
import org.veriagent.core.ledger.InvoiceLedger.Changes;
import org.veriagent.core.retry.RetryPolicies;
import org.veriagent.core.state.InvoiceState;
import org.veriagent.provider.DocumentPreparer;
import org.veriagent.provider.client.ProviderClient;
import org.veriagent.provider.client.ProviderProtocol;
import org.veriagent.provider.client.ProvidersProperties;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * provider-adapter-{proveedor} (tabla 11), con una estrategia por proveedor (alfa: ZIP por HTTP; beta: carpeta
 * SFTP). Para cada comprobante: aplica el mapeo vigente, lo verifica con el kit ANTES de agruparlo (ningún
 * comprobante defectuoso entra en un lote), lo persiste en PENDING_BATCH y lo envía en lotes con reintentos de
 * espera creciente. Si el proveedor sigue caído tras los reintentos, el lote va a la DLQ sin perder comprobantes.
 */
@Component
@ConditionalOnProperty(prefix = "veriagent.services", name = "provider-adapter", havingValue = "true", matchIfMissing = true)
public class ProviderAdapter {
    static final String PRODUCER = "provider-adapter";
    private static final Logger log = LoggerFactory.getLogger(ProviderAdapter.class);

    private final EventBus bus;
    private final InvoiceLedger ledger;
    private final DocumentPreparer preparer;
    private final ProviderClient client;
    private final ProvidersProperties providers;
    private final RetryPolicies retries;
    private final FlowEvents flow;
    private final JdbcClient jdbc;

    private final Map<String, Buffer> buffers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "adapter-flusher");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong seq = new AtomicLong();
    private final AtomicLong blocked = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger sending = new java.util.concurrent.atomic.AtomicInteger();
    private EventBus.Subscription subscription;

    public ProviderAdapter(EventBus bus, InvoiceLedger ledger, DocumentPreparer preparer, ProviderClient client,
                           ProvidersProperties providers, RetryPolicies retries, FlowEvents flow, JdbcClient jdbc) {
        this.bus = bus;
        this.ledger = ledger;
        this.preparer = preparer;
        this.client = client;
        this.providers = providers;
        this.retries = retries;
        this.flow = flow;
        this.jdbc = jdbc;
    }

    private record Pending(String invoiceKey, String correlationId, String content) {}

    private static final class Buffer {
        final String provider;
        final String country;
        final List<Pending> items = new ArrayList<>();
        Instant firstAt;

        Buffer(String provider, String country) {
            this.provider = provider;
            this.country = country;
        }
    }

    @PostConstruct
    void start() {
        subscription = bus.subscribe(Topics.ALL_PROVIDER_REQUESTS, PRODUCER, this::onRequest);
        flusher.scheduleWithFixedDelay(this::flushDue, 100, 100, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (subscription != null) subscription.close();
        flushAll();
        flusher.shutdownNow();
    }

    void onRequest(String topic, EventEnvelope e) {
        var req = Events.payload(e, Payloads.ProviderRequested.class);
        InvoiceRequest inv = req.invoice();
        String provider = req.provider();
        if (!providers.getEndpoints().containsKey(provider)) {
            flow.deadLetter(inv.invoiceKey(), inv.country().name(), PRODUCER, "ADAPTER", ErrorCategory.NON_RECOVERABLE, 0,
                "Proveedor sin adaptador configurado: " + provider, provider, null);
            return;
        }
        var record = ledger.find(inv.invoiceKey()).orElse(null);
        if (record == null || record.status() != InvoiceState.RECEIVED) {
            log.info("Se ignora {} para {}: no está en RECEIVED (entrega repetida)", inv.invoiceKey(), provider);
            return;
        }
        DocumentPreparer.Prepared p;
        try {
            p = preparer.prepare(inv, provider);
        } catch (DocumentPreparer.MissingMappingException ex) {
            flow.deadLetter(inv.invoiceKey(), inv.country().name(), PRODUCER, "ADAPTER", ErrorCategory.RECOVERABLE, 0,
                ex.getMessage(), provider, null);
            return;
        }
        if (!p.passed()) {
            blocked.incrementAndGet();
            var findings = p.verification().findings();
            ledger.transition(inv.invoiceKey(), InvoiceState.RECEIVED, InvoiceState.FAILED,
                Changes.reason("Bloqueado por el kit antes del envío (" + findings.size() + " hallazgos)")
                    .withProvider(provider, p.mappingVersion()).withPayload(p.content())
                    .withError("KIT_BLOCKED", findings.get(0).ruleId() + ": " + findings.get(0).message())
                    .withFindings(FlowEvents.findingsJson(findings)));
            flow.response(inv.invoiceKey(), inv.country().name(), PRODUCER, FinalStatus.FAILED, Channel.BATCH, provider,
                "KIT_BLOCKED", "Bloqueado por el kit de verificación antes del envío", null, findings);
            return;
        }
        ledger.transition(inv.invoiceKey(), InvoiceState.RECEIVED, InvoiceState.PENDING_BATCH,
            Changes.reason("Verificado por el kit y agrupado en lote").withProvider(provider, p.mappingVersion()).withPayload(p.content()));
        Buffer b = buffers.computeIfAbsent(provider + "/" + inv.country().name(), k -> new Buffer(provider, inv.country().name()));
        List<Pending> toSend = null;
        synchronized (b) {
            if (b.items.isEmpty()) b.firstAt = Instant.now();
            b.items.add(new Pending(inv.invoiceKey(), e.correlationId(), p.content()));
            if (b.items.size() >= cfg(provider).getBatchSize()) toSend = drain(b);
        }
        if (toSend != null) send(b.provider, b.country, toSend);
    }

    private ProvidersProperties.Provider cfg(String provider) {
        return providers.getEndpoints().get(provider);
    }

    /** Saca los elementos del buffer y marca un envío en curso; {@link #send} lo desmarca al terminar. */
    private List<Pending> drain(Buffer b) {
        sending.incrementAndGet();
        return drainItems(b);
    }

    private static List<Pending> drainItems(Buffer b) {
        List<Pending> out = new ArrayList<>(b.items);
        b.items.clear();
        b.firstAt = null;
        return out;
    }

    void flushDue() {
        for (Buffer b : buffers.values()) {
            List<Pending> toSend = null;
            synchronized (b) {
                if (!b.items.isEmpty() && Instant.now().isAfter(b.firstAt.plus(cfg(b.provider).getBatchWindow()))) toSend = drain(b);
            }
            if (toSend != null) {
                try {
                    send(b.provider, b.country, toSend);
                } catch (RuntimeException ex) {
                    log.error("Error al enviar el lote de {}: {}", b.provider, ex.toString(), ex);
                }
            }
        }
    }

    /** Envía de inmediato todo lo pendiente (también lo usa la API y el cierre ordenado). */
    public int flushAll() {
        int n = 0;
        for (Buffer b : buffers.values()) {
            List<Pending> toSend;
            synchronized (b) {
                toSend = b.items.isEmpty() ? null : drain(b);
            }
            if (toSend != null) {
                n += toSend.size();
                send(b.provider, b.country, toSend);
            }
        }
        return n;
    }

    /** Número de comprobantes agrupados todavía sin enviar. */
    public int pendingCount() {
        int n = 0;
        for (Buffer b : buffers.values()) synchronized (b) { n += b.items.size(); }
        return n;
    }

    public long blockedCount() { return blocked.get(); }

    /**
     * Envía lo pendiente y espera a que no quede ningún lote agrupado ni en envío (para escenarios y pruebas: el
     * vaciado periódico corre en otro hilo y la espera del bus no lo ve).
     */
    public boolean awaitIdle(java.time.Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        flushAll();
        while (System.nanoTime() < deadline) {
            if (sending.get() == 0 && pendingCount() == 0) return true;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void send(String provider, String country, List<Pending> items) {
        try {
            doSend(provider, country, items);
        } finally {
            sending.decrementAndGet();
        }
    }

    private void doSend(String provider, String country, List<Pending> items) {
        String batchId = provider + "-" + country.toLowerCase() + "-" + System.currentTimeMillis() + "-" + seq.incrementAndGet();
        String ext = "text".equals(cfg(provider).getSyncFormat()) ? ".seg" : provider.equals("alfa") ? ".xml" : ".json";
        List<ProviderProtocol.ManifestEntry> manifestEntries = new ArrayList<>();
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            String entry = i + ext;
            manifestEntries.add(new ProviderProtocol.ManifestEntry(i, entry, items.get(i).invoiceKey()));
            entries.put(entry, items.get(i).content());
        }
        var manifest = new ProviderProtocol.BatchManifest(batchId, provider, country, manifestEntries);
        Instant now = Instant.now();
        jdbc.sql("INSERT INTO provider_batch (batch_id, provider, country, size, status, attempts, created_at) VALUES (:b, :p, :c, :n, 'SENDING', 0, :now)")
            .param("b", batchId).param("p", provider).param("c", country).param("n", items.size()).param("now", Timestamp.from(now)).update();
        for (int i = 0; i < items.size(); i++) {
            jdbc.sql("INSERT INTO provider_batch_item (batch_id, invoice_key, position) VALUES (:b, :k, :i)")
                .param("b", batchId).param("k", items.get(i).invoiceKey()).param("i", i).update();
        }
        Retry retry = retries.providerCalls("lote-" + provider);
        int[] attempts = {0};
        try {
            Retry.decorateRunnable(retry, () -> {
                attempts[0]++;
                client.submitBatch(provider, manifest, entries);
            }).run();
        } catch (RuntimeException ex) {
            log.warn("El lote {} de {} falló tras {} intentos: {}", batchId, provider, attempts[0], ex.getMessage());
            jdbc.sql("UPDATE provider_batch SET status = 'FAILED', attempts = :a, last_error = :e WHERE batch_id = :b")
                .param("a", attempts[0]).param("e", trunc(ex.getMessage())).param("b", batchId).update();
            boolean recoverable = ex instanceof RetryPolicies.RecoverableException;
            for (Pending it : items) {
                CorrelationContext.run(it.correlationId(), () -> flow.deadLetter(it.invoiceKey(), country, PRODUCER, "PROVIDER",
                    recoverable ? ErrorCategory.RECOVERABLE : ErrorCategory.NON_RECOVERABLE, attempts[0],
                    "Lote " + batchId + " no entregado: " + ex.getMessage(), provider, null));
            }
            return;
        }
        jdbc.sql("UPDATE provider_batch SET status = 'SENT', attempts = :a, sent_at = :now WHERE batch_id = :b")
            .param("a", attempts[0]).param("now", Timestamp.from(Instant.now())).param("b", batchId).update();
        for (Pending it : items) {
            CorrelationContext.run(it.correlationId(), () -> ledger.transition(it.invoiceKey(), InvoiceState.PENDING_BATCH,
                InvoiceState.SENT, Changes.reason("Enviado en el lote " + batchId + (attempts[0] > 1 ? " tras " + attempts[0] + " intentos" : ""))));
        }
        log.info("Lote {} enviado a {} con {} comprobantes ({} intentos)", batchId, provider, items.size(), attempts[0]);
    }

    private static String trunc(String s) {
        return s == null ? null : s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
