package org.veriagent.responseprocessor;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.events.EventEnvelope;
import org.veriagent.contracts.events.EventTypes;
import org.veriagent.contracts.events.Payloads;
import org.veriagent.contracts.events.Payloads.Channel;
import org.veriagent.contracts.events.Payloads.ErrorCategory;
import org.veriagent.contracts.events.Payloads.FinalStatus;
import org.veriagent.contracts.events.Topics;
import org.veriagent.contracts.model.Country;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.bus.Events;
import org.veriagent.core.bus.FlowEvents;
import org.veriagent.core.correlation.CorrelationContext;
import org.veriagent.core.io.SafeFiles;
import org.veriagent.core.ledger.InvoiceLedger;
import org.veriagent.core.ledger.InvoiceLedger.Changes;
import org.veriagent.core.ledger.InvoiceRecord;
import org.veriagent.core.state.InvoiceState;
import org.veriagent.provider.client.ProviderProtocol;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * response-processor (tabla 11): lee los resultados de los lotes de la carpeta de retorno, los publica como
 * {@code {pais}.{proveedor}.response.v1} y los correlaciona por comprobante para actualizar el estado.
 * ADR-06: el resultado del proveedor es la fuente primaria y la constancia oficial de la autoridad es el respaldo;
 * la conciliación usa la constancia para los comprobantes enviados que no recibieron respuesta.
 */
public class ResponseProcessor {
    static final String PRODUCER = "response-processor";
    private static final Logger log = LoggerFactory.getLogger(ResponseProcessor.class);

    private final EventBus bus;
    private final InvoiceLedger ledger;
    private final FlowEvents flow;
    private final Path returnRoot;
    private final ResponseProperties props;
    private final AtomicLong authorized = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong fromReceipts = new AtomicLong();
    private EventBus.Subscription subscription;

    public ResponseProcessor(EventBus bus, InvoiceLedger ledger, FlowEvents flow, Path returnRoot, ResponseProperties props) {
        this.bus = bus;
        this.ledger = ledger;
        this.flow = flow;
        this.returnRoot = returnRoot;
        this.props = props;
    }

    @PostConstruct
    void start() {
        subscription = bus.subscribe(Topics.ALL_PROVIDER_RESPONSES, PRODUCER, this::onProviderResponse);
    }

    @PreDestroy
    void stop() {
        if (subscription != null) subscription.close();
    }

    /** Un archivo de resultados de lote (fuente primaria): cada decisión se publica como respuesta del proveedor. */
    public int processResultFile(String json) {
        var result = ContractJson.mapper().readValue(json, ProviderProtocol.BatchResult.class);
        int n = 0;
        for (var d : result.results()) {
            Optional<InvoiceRecord> rec = ledger.find(d.documentId());
            if (rec.isEmpty()) {
                log.warn("Respuesta de {} para un comprobante desconocido: {}", result.provider(), d.documentId());
                continue;
            }
            publish(rec.get(), result.provider(), result.batchId(), d, "PROVIDER");
            n++;
        }
        return n;
    }

    private void publish(InvoiceRecord rec, String provider, String batchId, ProviderProtocol.Decision d, String source) {
        CorrelationContext.run(rec.correlationId(), () -> bus.publish(
            Topics.providerResponse(Country.valueOf(rec.country()), provider),
            Events.of(EventTypes.PROVIDER_RESPONDED, rec.country(), rec.invoiceKey(), PRODUCER,
                new Payloads.ProviderResponded(provider, batchId,
                    d.authorized() ? Payloads.Outcome.AUTHORIZED : Payloads.Outcome.REJECTED, d.code(), d.message(),
                    d.authorizationNumber(), d.receiptSha256(), source))));
    }

    void onProviderResponse(String topic, EventEnvelope e) throws InterruptedException {
        var r = Events.payload(e, Payloads.ProviderResponded.class);
        String key = e.invoiceKey();
        InvoiceRecord rec = awaitSent(key);
        if (rec == null) return;
        if (rec.status() != InvoiceState.SENT) {
            duplicates.incrementAndGet();
            log.info("Respuesta repetida o tardía para {} (estado {}): se ignora", key, rec.status());
            return;
        }
        Channel channel = Channel.valueOf(rec.channel());
        String sourceLabel = "AUTHORITY_RECEIPT".equals(r.source()) ? " (constancia oficial, respaldo)" : "";
        if (r.outcome() == Payloads.Outcome.AUTHORIZED) {
            ledger.transition(key, InvoiceState.SENT, InvoiceState.AUTHORIZED,
                Changes.reason("Autorizado por " + r.provider() + sourceLabel).withAuthorization(r.authorizationNumber()));
            authorized.incrementAndGet();
            if (!sourceLabel.isEmpty()) fromReceipts.incrementAndGet();
            flow.response(key, rec.country(), PRODUCER, FinalStatus.AUTHORIZED, channel, r.provider(), "OK",
                "Autorizado" + sourceLabel, r.authorizationNumber(), null);
        } else {
            ledger.transition(key, InvoiceState.SENT, InvoiceState.FAILED,
                Changes.reason("Rechazado por " + r.provider() + ": " + r.providerCode()).withError("PROVIDER_REJECTED", r.providerCode() + ": " + r.message()));
            rejected.incrementAndGet();
            flow.response(key, rec.country(), PRODUCER, FinalStatus.FAILED, channel, r.provider(), "PROVIDER_REJECTED", r.message(), null, null);
            flow.deadLetter(key, rec.country(), PRODUCER, "PROVIDER", ErrorCategory.NON_RECOVERABLE, rec.retryCount(),
                "Rechazo del proveedor " + r.provider() + " (" + r.providerCode() + "): " + r.message(), r.provider(), null);
        }
    }

    /** El resultado puede llegar antes de que el adaptador termine de marcar el lote como SENT: se espera un poco. */
    private InvoiceRecord awaitSent(String key) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            InvoiceRecord rec = ledger.find(key).orElse(null);
            if (rec == null) {
                log.warn("Respuesta para un comprobante desconocido: {}", key);
                return null;
            }
            if (rec.status() != InvoiceState.PENDING_BATCH || System.nanoTime() > deadline) return rec;
            Thread.sleep(50);
        }
    }

    /**
     * Conciliación (ADR-06): para los comprobantes SENT sin respuesta tras el período de gracia busca la constancia
     * oficial; si no aparece antes del plazo máximo, el comprobante va a la DLQ como recuperable.
     */
    public ReconcileResult reconcile() {
        int receipts = 0, timeouts = 0;
        Instant now = Instant.now();
        for (String key : ledger.keysIn(InvoiceState.SENT)) {
            InvoiceRecord rec = ledger.find(key).orElse(null);
            if (rec == null || rec.sentAt() == null || rec.provider() == null) continue;
            Duration age = Duration.between(rec.sentAt(), now);
            if (age.compareTo(props.getReceiptGrace()) < 0) continue;
            Path receipt = returnRoot.resolve(rec.provider()).resolve("receipts").resolve(SafeFiles.sanitize(key) + ".receipt.json");
            if (Files.exists(receipt)) {
                try {
                    var r = ContractJson.mapper().readValue(Files.readString(receipt), ProviderProtocol.Receipt.class);
                    publish(rec, rec.provider(), null, new ProviderProtocol.Decision(key, r.status(), "CONSTANCIA",
                        "Constancia oficial de la autoridad", r.authorizationNumber(), r.documentSha256()), "AUTHORITY_RECEIPT");
                    receipts++;
                } catch (IOException | RuntimeException ex) {
                    log.warn("Constancia ilegible para {}: {}", key, ex.toString());
                }
            } else if (age.compareTo(props.getResponseTimeout()) >= 0) {
                CorrelationContext.run(rec.correlationId(), () -> flow.deadLetter(key, rec.country(), PRODUCER, "RESPONSE",
                    ErrorCategory.RECOVERABLE, rec.retryCount(), "Sin respuesta del proveedor " + rec.provider()
                        + " ni constancia oficial tras " + age.toSeconds() + " s", rec.provider(), null));
                timeouts++;
            }
        }
        return new ReconcileResult(receipts, timeouts);
    }

    public record ReconcileResult(int fromReceipts, int timedOut) {}

    public java.util.Map<String, Long> stats() {
        return java.util.Map.of("authorized", authorized.get(), "rejected", rejected.get(), "duplicates", duplicates.get(),
            "authorizedFromReceipts", fromReceipts.get());
    }
}
