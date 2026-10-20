package org.veriagent.dlqhandler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.events.Payloads;
import org.veriagent.contracts.events.Topics;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.core.audit.AuditService;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.bus.FlowEvents;
import org.veriagent.core.dlq.DlqRepository;
import org.veriagent.core.ledger.InvoiceLedger;
import org.veriagent.core.ledger.InvoiceLedger.Changes;
import org.veriagent.core.security.DemoTokenStore;
import org.veriagent.core.security.Role;
import org.veriagent.core.state.InvoiceState;
import org.veriagent.core.testsupport.CoreTestApplication;
import org.veriagent.core.testsupport.Fixtures;
import org.veriagent.core.testsupport.TestTokens;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CoreTestApplication.class, properties = "veriagent.dlq.auto-retry-enabled=false")
@AutoConfigureMockMvc
class DlqHandlerTest {
    @Autowired MockMvc mvc;
    @Autowired EventBus bus;
    @Autowired FlowEvents flow;
    @Autowired InvoiceLedger ledger;
    @Autowired DlqRepository dlq;
    @Autowired AuditService audit;
    @Autowired DemoTokenStore store;
    TestTokens tokens;

    @BeforeEach
    void setUp() { tokens = new TestTokens(store); }

    private InvoiceRequest quarantined() {
        InvoiceRequest inv = Fixtures.coInvoice(Fixtures.number());
        ledger.register(inv, Payloads.Channel.BATCH, ContractJson.mapper().writeValueAsString(inv), "lote.json");
        ledger.transition(inv.invoiceKey(), InvoiceState.RECEIVED, InvoiceState.SENT, Changes.reason("x").withProvider("alfa", 1));
        flow.deadLetter(inv.invoiceKey(), "CO", "prueba", "PROVIDER", Payloads.ErrorCategory.RECOVERABLE, 5, "Proveedor caído", "alfa", null);
        assertTrue(bus.awaitQuiescence(Duration.ofSeconds(5)));
        return inv;
    }

    private long entryFor(InvoiceRequest inv) {
        return dlq.openFor(inv.invoiceKey()).orElseThrow().id();
    }

    @Test
    @DisplayName("El evento de cuarentena mueve el comprobante a DLQ y registra la entrada clasificada")
    void quarantines() {
        InvoiceRequest inv = quarantined();
        assertEquals(InvoiceState.DLQ, ledger.find(inv.invoiceKey()).orElseThrow().status());
        var e = dlq.openFor(inv.invoiceKey()).orElseThrow();
        assertEquals("PROVIDER", e.stage());
        assertEquals("RECOVERABLE", e.category());
        assertEquals(5, e.attempts());
    }

    @Test
    @DisplayName("Reproceso: el agente recibe 403, la suplantación 403, y el operador reprocesa con su identidad del token")
    void reprocessIsAuthorized() throws Exception {
        InvoiceRequest inv = quarantined();
        long id = entryFor(inv);
        mvc.perform(post("/api/dlq/" + id + "/reprocess")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/dlq/" + id + "/reprocess").header("Authorization", tokens.agent())).andExpect(status().isForbidden());
        mvc.perform(post("/api/dlq/" + id + "/reprocess").header("Authorization", tokens.reviewer())).andExpect(status().isForbidden());
        String op = tokens.bearer("operadora.real", Role.OPERATOR);
        mvc.perform(post("/api/dlq/" + id + "/reprocess").header("Authorization", op).contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"x\",\"operator\":\"otra.persona\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IMPERSONATION"));
        assertEquals(InvoiceState.DLQ, ledger.find(inv.invoiceKey()).orElseThrow().status(), "la suplantación no tuvo efecto");

        List<String> republished = new CopyOnWriteArrayList<>();
        try (var s = bus.subscribe(Topics.ALL_INVOICE_REQUESTS, "prueba-reproceso", (t, e) -> republished.add(e.invoiceKey()))) {
            mvc.perform(post("/api/dlq/" + id + "/reprocess").header("Authorization", op).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"comment\":\"proveedor restablecido\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reprocessedBy").value("operadora.real"));
            assertTrue(bus.awaitQuiescence(Duration.ofSeconds(5)));
            assertEquals(List.of(inv.invoiceKey()), republished);
        }
        var rec = ledger.find(inv.invoiceKey()).orElseThrow();
        assertEquals(InvoiceState.RECEIVED, rec.status());
        assertEquals(1, rec.retryCount());
        var entry = dlq.find(id).orElseThrow();
        assertEquals(DlqRepository.Status.REPROCESSED, entry.status());
        assertEquals("operadora.real", entry.resolvedBy());
        var a = audit.forTarget("comprobante:" + inv.invoiceKey());
        assertEquals("operadora.real", a.get(a.size() - 1).actor());
        mvc.perform(post("/api/dlq/" + id + "/reprocess").header("Authorization", op)).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Una entrada inválida de la ingesta no se puede reprocesar: debe corregirse en origen (409)")
    void invalidInputIsNotReprocessable() throws Exception {
        long id = dlq.open(null, "lote.json#9", "CO", null, "INGEST", "NON_RECOVERABLE", 0, "esquema", null, "{}");
        mvc.perform(post("/api/dlq/" + id + "/reprocess").header("Authorization", tokens.operator()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_REPROCESSABLE"));
        mvc.perform(post("/api/dlq/" + id + "/discard").header("Authorization", tokens.agent())).andExpect(status().isForbidden());
        mvc.perform(post("/api/dlq/" + id + "/discard").header("Authorization", tokens.operator())).andExpect(status().isOk());
        assertEquals(DlqRepository.Status.DISCARDED, dlq.find(id).orElseThrow().status());
    }

    @Test
    @DisplayName("Triaje: el agente registra una recomendación con su identidad; no reprocesa ni cambia el estado")
    void triageIsOnlyARecommendation() throws Exception {
        InvoiceRequest inv = quarantined();
        long id = entryFor(inv);
        String body = "{\"classification\":\"PROVEEDOR_RECHAZO\",\"recommendation\":\"REPROCESAR_TRAS_CORRECCION\",\"evidence\":[\"x\"]}";
        mvc.perform(post("/api/dlq/" + id + "/triage").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/dlq/" + id + "/triage").header("Authorization", tokens.operator()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/dlq/" + id + "/triage").header("Authorization", tokens.agent()).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.triage.triagedBy").value("agente.prueba"));
        var e = dlq.find(id).orElseThrow();
        assertEquals(DlqRepository.Status.OPEN, e.status());
        assertTrue(e.triage().contains("REPROCESAR_TRAS_CORRECCION"));
        assertEquals(InvoiceState.DLQ, ledger.find(inv.invoiceKey()).orElseThrow().status());
    }
}
