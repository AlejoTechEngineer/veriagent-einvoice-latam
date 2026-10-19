package org.veriagent.invoicefacade;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.events.Payloads;
import org.veriagent.contracts.events.Topics;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.bus.Events;
import org.veriagent.core.ledger.InvoiceLedger;
import org.veriagent.core.security.DemoTokenStore;
import org.veriagent.core.state.InvoiceState;
import org.veriagent.core.testsupport.CoreTestApplication;
import org.veriagent.core.testsupport.Fixtures;
import org.veriagent.core.testsupport.StubProvider;
import org.veriagent.core.testsupport.TestTokens;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CoreTestApplication.class)
@AutoConfigureMockMvc
class InvoiceFacadeTest {
    static final StubProvider ALFA = new StubProvider();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("veriagent.providers.endpoints.alfa.base-url", ALFA::baseUrl);
        r.add("veriagent.providers.seed-mappings.[alfa/CO]", () -> "agentic-plane/golden/mapping-alfa-v2.json");
    }

    @AfterAll
    static void close() { ALFA.close(); }

    @Autowired MockMvc mvc;
    @Autowired InvoiceLedger ledger;
    @Autowired EventBus bus;
    @Autowired DemoTokenStore store;
    TestTokens tokens;

    @BeforeEach
    void setUp() {
        tokens = new TestTokens(store);
        ALFA.mode(StubProvider.Mode.OK);
    }

    private String body(InvoiceRequest inv) {
        return ContractJson.mapper().writeValueAsString(inv);
    }

    @Test
    @DisplayName("S1: emisión síncrona correcta, autorizada en la misma llamada y con respuesta publicada")
    void issuesSynchronously() throws Exception {
        List<Payloads.InvoiceResponded> responses = new CopyOnWriteArrayList<>();
        try (var s = bus.subscribe(Topics.ALL_INVOICE_RESPONSES, "prueba-fachada", (t, e) -> responses.add(Events.payload(e, Payloads.InvoiceResponded.class)))) {
            InvoiceRequest inv = Fixtures.coInvoice(Fixtures.number());
            mvc.perform(post("/api/invoices").header("Authorization", tokens.operator()).contentType(MediaType.APPLICATION_JSON).content(body(inv)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.provider").value("alfa"))
                .andExpect(jsonPath("$.mappingVersion").value(1))
                .andExpect(jsonPath("$.duplicate").value(false));
            var h = ledger.history(inv.invoiceKey());
            assertEquals(List.of(InvoiceState.RECEIVED, InvoiceState.SENT, InvoiceState.AUTHORIZED), h.stream().map(c -> c.to()).toList());
            assertEquals("operador.prueba", h.get(0).actor(), "el actor es el del token");
            assertTrue(bus.awaitQuiescence(Duration.ofSeconds(5)));
            assertEquals(1, responses.size());
            assertEquals(Payloads.FinalStatus.AUTHORIZED, responses.get(0).status());
        }
    }

    @Test
    @DisplayName("S6: un envío duplicado devuelve el registro existente y no vuelve a llamar al proveedor")
    void idempotent() throws Exception {
        InvoiceRequest inv = Fixtures.coInvoice(Fixtures.number());
        mvc.perform(post("/api/invoices").header("Authorization", tokens.operator()).contentType(MediaType.APPLICATION_JSON).content(body(inv)))
            .andExpect(status().isOk());
        int calls = ALFA.documents();
        ObjectNode again = ContractJson.mapper().valueToTree(inv);
        again.put("correlationId", "otra-correlacion-1");
        mvc.perform(post("/api/invoices").header("Authorization", tokens.operator()).header("Idempotency-Key", "cliente-1")
                .contentType(MediaType.APPLICATION_JSON).content(ContractJson.mapper().writeValueAsString(again)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true)).andExpect(jsonPath("$.status").value("AUTHORIZED"))
            .andExpect(jsonPath("$.correlationId").value(inv.correlationId()));
        assertEquals(calls, ALFA.documents());
    }

    @Test
    @DisplayName("Validación antes de procesar: esquema e invariantes devuelven 400 sin registrar nada")
    void validatesBeforeProcessing() throws Exception {
        ObjectNode bad = ContractJson.mapper().valueToTree(Fixtures.coInvoice(Fixtures.number()));
        bad.remove("customer");
        mvc.perform(post("/api/invoices").header("Authorization", tokens.operator()).contentType(MediaType.APPLICATION_JSON).content(bad.toString()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONTRACT_VIOLATION"));
        InvoiceRequest inv = Fixtures.coInvoice(Fixtures.number());
        ObjectNode incoherent = ContractJson.mapper().valueToTree(inv);
        ((ObjectNode) incoherent.get("totals")).put("payableAmount", "1.00");
        mvc.perform(post("/api/invoices").header("Authorization", tokens.operator()).contentType(MediaType.APPLICATION_JSON).content(incoherent.toString()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVARIANTS"));
        assertTrue(ledger.find(inv.invoiceKey()).isEmpty());
    }

    @Test
    @DisplayName("Proveedor caído: 503 tras los reintentos y evento de DLQ recuperable, sin perder el comprobante")
    void providerDown() throws Exception {
        ALFA.mode(StubProvider.Mode.DOWN);
        List<Payloads.InvoiceDeadLettered> dlq = new CopyOnWriteArrayList<>();
        try (var s = bus.subscribe(Topics.ALL_INVOICE_DLQ, "prueba-fachada-dlq", (t, e) -> dlq.add(Events.payload(e, Payloads.InvoiceDeadLettered.class)))) {
            InvoiceRequest inv = Fixtures.coInvoice(Fixtures.number());
            mvc.perform(post("/api/invoices").header("Authorization", tokens.operator()).contentType(MediaType.APPLICATION_JSON).content(body(inv)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.reasonCode").value("PROVIDER_UNAVAILABLE"));
            assertTrue(bus.awaitQuiescence(Duration.ofSeconds(5)));
            assertEquals(1, dlq.size());
            assertEquals(3, dlq.get(0).attempts());
            assertEquals(InvoiceState.SENT, ledger.find(inv.invoiceKey()).orElseThrow().status());
        }
    }

    @Test
    @DisplayName("Rechazo del proveedor: 422, FAILED y evento de DLQ no recuperable para el triaje")
    void providerRejects() throws Exception {
        ALFA.mode(StubProvider.Mode.REJECT);
        InvoiceRequest inv = Fixtures.coInvoice(Fixtures.number());
        mvc.perform(post("/api/invoices").header("Authorization", tokens.operator()).contentType(MediaType.APPLICATION_JSON).content(body(inv)))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.reasonCode").value("PROVIDER_REJECTED"))
            .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    @DisplayName("Roles: el agente y el revisor no emiten comprobantes (403); sin token 401")
    void roles() throws Exception {
        InvoiceRequest inv = Fixtures.coInvoice(Fixtures.number());
        mvc.perform(post("/api/invoices").contentType(MediaType.APPLICATION_JSON).content(body(inv))).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/invoices").header("Authorization", tokens.agent()).contentType(MediaType.APPLICATION_JSON).content(body(inv)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/invoices").header("Authorization", tokens.reviewer()).contentType(MediaType.APPLICATION_JSON).content(body(inv)))
            .andExpect(status().isForbidden());
    }
}
