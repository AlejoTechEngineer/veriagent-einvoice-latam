package org.veriagent.core.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.veriagent.contracts.events.Payloads.Channel;
import org.veriagent.contracts.model.Country;
import org.veriagent.contracts.model.DocumentType;
import org.veriagent.contracts.model.IdType;
import org.veriagent.contracts.model.InvoiceLine;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.contracts.model.Party;
import org.veriagent.contracts.model.TaxCode;
import org.veriagent.contracts.model.Totals;
import org.veriagent.core.correlation.CorrelationContext;
import org.veriagent.core.state.IllegalTransitionException;
import org.veriagent.core.state.InvoiceState;
import org.veriagent.core.testsupport.CoreTestApplication;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = CoreTestApplication.class)
class InvoiceLedgerTest {
    @Autowired InvoiceLedger ledger;

    static InvoiceRequest invoice(String number, String correlation) {
        var line = new InvoiceLine(1, "Servicio", new BigDecimal("2"), new BigDecimal("50.00"), new BigDecimal("100.00"),
            TaxCode.IVA19, new BigDecimal("19.00"));
        return new InvoiceRequest(Country.CO, correlation, number, DocumentType.INVOICE, LocalDate.of(2026, 10, 1), "COP", null,
            new Party("Emisor", "900123456-8", IdType.NIT, null), new Party("Cliente", "1020304050", IdType.CC, "c@ejemplo.test"),
            List.of(line), new Totals(new BigDecimal("100.00"), new BigDecimal("19.00"), new BigDecimal("119.00")));
    }

    @Test
    @DisplayName("Idempotencia: el mismo comprobante enviado dos veces se registra una sola vez")
    void registersOnce() {
        String number = "FA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        var first = ledger.register(invoice(number, "corr-" + UUID.randomUUID()), Channel.SYNC, "{}");
        var second = ledger.register(invoice(number, "corr-" + UUID.randomUUID()), Channel.BATCH, "{}");
        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(first.record().id(), second.record().id());
        assertEquals(Channel.SYNC.name(), second.record().channel());
        assertEquals(1, ledger.history(first.record().invoiceKey()).size());
    }

    @Test
    @DisplayName("Idempotencia bajo concurrencia: 20 envíos simultáneos crean exactamente un registro")
    void registersOnceUnderConcurrency() throws Exception {
        String number = "FA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        AtomicInteger created = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) {
                fs.add(pool.submit(() -> {
                    if (ledger.register(invoice(number, "corr-" + UUID.randomUUID()), Channel.BATCH, "{}").created()) {
                        created.incrementAndGet();
                    }
                }));
            }
            for (Future<?> f : fs) f.get();
        } finally {
            pool.shutdown();
        }
        assertEquals(1, created.get());
    }

    @Test
    @DisplayName("Transiciones válidas con línea de tiempo, correlación y marcas de tiempo")
    void validTransitions() {
        String corr = "corr-" + UUID.randomUUID();
        var reg = ledger.register(invoice("FA-T" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), corr), Channel.BATCH, "{}");
        String key = reg.record().invoiceKey();
        CorrelationContext.run(corr, () -> {
            ledger.transition(key, InvoiceState.RECEIVED, InvoiceState.PENDING_BATCH,
                InvoiceLedger.Changes.reason("Agrupado").withProvider("alfa", 1).withPayload("<Invoice/>"));
            ledger.transition(key, InvoiceState.PENDING_BATCH, InvoiceState.SENT, InvoiceLedger.Changes.reason("Lote enviado"));
            ledger.transition(key, InvoiceState.SENT, InvoiceState.AUTHORIZED,
                InvoiceLedger.Changes.reason("Autorizado").withAuthorization("AUT-1"));
        });
        InvoiceRecord r = ledger.find(key).orElseThrow();
        assertEquals(InvoiceState.AUTHORIZED, r.status());
        assertEquals("alfa", r.provider());
        assertEquals(1, r.mappingVersion());
        assertEquals("AUT-1", r.authorizationNumber());
        assertNotNull(r.sentAt());
        assertNotNull(r.authorizedAt());
        var h = ledger.history(key);
        assertEquals(List.of(InvoiceState.RECEIVED, InvoiceState.PENDING_BATCH, InvoiceState.SENT, InvoiceState.AUTHORIZED),
            h.stream().map(InvoiceLedger.StateChange::to).toList());
        assertTrue(h.stream().allMatch(c -> corr.equals(c.correlationId())));
        assertEquals("sistema", h.get(1).actor());
    }

    @Test
    @DisplayName("Transiciones inválidas o concurrentes se rechazan y no dejan rastro")
    void invalidTransitions() {
        var reg = ledger.register(invoice("FA-I" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), "corr-" + UUID.randomUUID()), Channel.SYNC, "{}");
        String key = reg.record().invoiceKey();
        assertThrows(IllegalTransitionException.class,
            () -> ledger.transition(key, InvoiceState.RECEIVED, InvoiceState.AUTHORIZED, InvoiceLedger.Changes.reason("x")));
        assertThrows(IllegalTransitionException.class,
            () -> ledger.transition(key, InvoiceState.SENT, InvoiceState.AUTHORIZED, InvoiceLedger.Changes.reason("x")));
        ledger.transition(key, InvoiceState.RECEIVED, InvoiceState.SENT, InvoiceLedger.Changes.reason("ok"));
        assertThrows(IllegalTransitionException.class,
            () -> ledger.transition(key, InvoiceState.RECEIVED, InvoiceState.SENT, InvoiceLedger.Changes.reason("otra vez")));
        assertEquals(2, ledger.history(key).size());
        assertEquals(InvoiceState.SENT, ledger.find(key).orElseThrow().status());
    }

    @Test
    @DisplayName("Cuarentena y reproceso: DLQ vuelve a RECEIVED con el error limpio y el reintento contado")
    void dlqAndReprocess() {
        var reg = ledger.register(invoice("FA-D" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(), "corr-" + UUID.randomUUID()), Channel.BATCH, "{}");
        String key = reg.record().invoiceKey();
        ledger.transition(key, InvoiceState.RECEIVED, InvoiceState.DLQ,
            InvoiceLedger.Changes.reason("Proveedor caído").withError("PROVIDER_DOWN", "sin respuesta").retried());
        assertEquals("PROVIDER_DOWN", ledger.find(key).orElseThrow().errorCode());
        ledger.transition(key, InvoiceState.DLQ, InvoiceState.RECEIVED, InvoiceLedger.Changes.reason("Reproceso"));
        InvoiceRecord r = ledger.find(key).orElseThrow();
        assertEquals(InvoiceState.RECEIVED, r.status());
        assertNull(r.errorCode());
        assertEquals(1, r.retryCount());
        assertTrue(ledger.countByStatus().keySet().containsAll(List.of(InvoiceState.values())));
    }

    @Test
    @DisplayName("Máquina de estados: AUTHORIZED es final y DLQ solo sale por reproceso")
    void stateMachine() {
        assertTrue(InvoiceState.AUTHORIZED.next().isEmpty());
        assertEquals(java.util.Set.of(InvoiceState.RECEIVED), InvoiceState.DLQ.next());
        assertTrue(InvoiceState.RECEIVED.canMoveTo(InvoiceState.SENT));
        assertFalse(InvoiceState.FAILED.canMoveTo(InvoiceState.AUTHORIZED));
    }
}
