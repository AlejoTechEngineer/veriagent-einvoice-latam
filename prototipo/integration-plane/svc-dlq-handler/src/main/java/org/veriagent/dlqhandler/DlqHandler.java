package org.veriagent.dlqhandler;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.events.EventEnvelope;
import org.veriagent.contracts.events.EventTypes;
import org.veriagent.contracts.events.Payloads;
import org.veriagent.contracts.events.Topics;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.core.audit.AuditService;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.bus.Events;
import org.veriagent.core.bus.FlowEvents;
import org.veriagent.core.correlation.CorrelationContext;
import org.veriagent.core.dlq.DlqRepository;
import org.veriagent.core.ledger.InvoiceLedger;
import org.veriagent.core.ledger.InvoiceLedger.Changes;
import org.veriagent.core.ledger.InvoiceRecord;
import org.veriagent.core.security.CurrentActor;
import org.veriagent.core.state.InvoiceState;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * dlq-handler (tabla 11): único dueño del estado DLQ. Recibe los eventos de cuarentena, mueve el comprobante a DLQ
 * y registra la entrada con su clasificación. Ofrece el reproceso autorizado (solo OPERATOR, o SYSTEM en el
 * reintento controlado de los errores recuperables) y el descarte.
 */
public class DlqHandler {
    static final String GROUP = "dlq-handler";
    private static final Logger log = LoggerFactory.getLogger(DlqHandler.class);

    private final EventBus bus;
    private final InvoiceLedger ledger;
    private final DlqRepository dlq;
    private final AuditService audit;
    private final DlqProperties props;
    private final ScheduledExecutorService autoRetry = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "dlq-reintento-controlado");
        t.setDaemon(true);
        return t;
    });
    private EventBus.Subscription subscription;

    public DlqHandler(EventBus bus, InvoiceLedger ledger, DlqRepository dlq, AuditService audit, DlqProperties props) {
        this.bus = bus;
        this.ledger = ledger;
        this.dlq = dlq;
        this.audit = audit;
        this.props = props;
    }

    @PostConstruct
    void start() {
        subscription = bus.subscribe(Topics.ALL_INVOICE_DLQ, GROUP, this::onDeadLetter);
        long every = props.getAutoRetryInterval().toMillis();
        if (props.isAutoRetryEnabled() && every > 0) autoRetry.scheduleWithFixedDelay(this::autoRetryDue, every, every, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (subscription != null) subscription.close();
        autoRetry.shutdownNow();
    }

    void onDeadLetter(String topic, EventEnvelope e) {
        var d = Events.payload(e, Payloads.InvoiceDeadLettered.class);
        String key = e.invoiceKey();
        InvoiceRecord rec = key == null ? null : ledger.find(key).orElse(null);
        if (rec != null && rec.status() != InvoiceState.DLQ) {
            if (!rec.status().canMoveTo(InvoiceState.DLQ)) {
                log.warn("Evento de DLQ para {} en estado {}: no se puede poner en cuarentena", key, rec.status());
                return;
            }
            ledger.transition(key, rec.status(), InvoiceState.DLQ, Changes.reason("Cuarentena (" + d.stage() + ", " + d.category() + ")")
                .withError(rec.status() == InvoiceState.FAILED && rec.errorCode() != null ? rec.errorCode() : d.stage() + "_" + d.category(), d.lastError()));
        }
        dlq.open(key, key == null ? "evento:" + e.eventId() : "comprobante:" + key, e.country(), d.provider(), d.stage(),
            d.category().name(), d.attempts(), d.lastError(), FlowEvents.findingsJson(d.findings()), null);
    }

    /** Resultado de un reproceso. */
    public record Reprocessed(long entryId, String invoiceKey, String reprocessedBy, InvoiceState status) {}

    /** Reproceso autorizado: DLQ → RECEIVED y vuelta al flujo asíncrono con el mapeo vigente. */
    @Transactional
    public Reprocessed reprocess(long entryId, String comment) {
        var entry = dlq.find(entryId).orElseThrow(() -> new java.util.NoSuchElementException("Entrada de DLQ no encontrada: " + entryId));
        if (entry.status() != DlqRepository.Status.OPEN) throw new IllegalStateException("La entrada " + entryId + " ya fue " + entry.status());
        if (entry.invoiceKey() == null) {
            throw new IllegalStateException("La entrada " + entryId + " es una entrada inválida de la ingesta: debe corregirse en origen y volver a enviarse");
        }
        InvoiceRecord rec = ledger.find(entry.invoiceKey()).orElseThrow();
        String actor = CurrentActor.get().subject();
        ledger.transition(rec.invoiceKey(), InvoiceState.DLQ, InvoiceState.RECEIVED,
            Changes.reason("Reproceso autorizado por " + actor + (comment == null || comment.isBlank() ? "" : ": " + comment)).retried());
        dlq.resolve(entryId, DlqRepository.Status.REPROCESSED, actor, comment);
        audit.record("DLQ_REPROCESS", "comprobante:" + rec.invoiceKey(), "entrada=" + entryId + (comment == null ? "" : " " + comment));
        InvoiceRequest inv = ContractJson.mapper().readValue(rec.canonicalPayload(), InvoiceRequest.class);
        CorrelationContext.run(rec.correlationId(), () -> bus.publish(Topics.invoiceRequest(inv.country()),
            Events.of(EventTypes.INVOICE_REQUESTED, inv.country().name(), inv.invoiceKey(), GROUP,
                new Payloads.InvoiceRequested(Payloads.Channel.BATCH, rec.sourceFile(), inv))));
        return new Reprocessed(entryId, rec.invoiceKey(), actor, InvoiceState.RECEIVED);
    }

    public void discard(long entryId, String reason) {
        dlq.resolve(entryId, DlqRepository.Status.DISCARDED, CurrentActor.get().subject(), reason);
        audit.record("DLQ_DISCARD", "dlq:" + entryId, reason);
    }

    /** Reintento controlado de los errores recuperables, con espera creciente entre intentos. */
    void autoRetryDue() {
        try {
            for (var e : dlq.list(DlqRepository.Status.OPEN, 200)) {
                if (!"RECOVERABLE".equals(e.category()) || e.invoiceKey() == null) continue;
                InvoiceRecord rec = ledger.find(e.invoiceKey()).orElse(null);
                if (rec == null || rec.status() != InvoiceState.DLQ || rec.retryCount() >= props.getAutoRetryMax()) continue;
                Duration wait = props.getAutoRetryInterval().multipliedBy(1L << Math.min(rec.retryCount(), 6));
                if (Instant.now().isBefore(e.createdAt().plus(wait))) continue;
                reprocess(e.id(), "reintento controlado automático " + (rec.retryCount() + 1) + " de " + props.getAutoRetryMax());
            }
        } catch (RuntimeException ex) {
            log.warn("Reintento controlado: {}", ex.toString());
        }
    }
}
