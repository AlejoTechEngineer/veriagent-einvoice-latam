package org.veriagent.provideradapter;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.events.EventTypes;
import org.veriagent.contracts.events.Payloads;
import org.veriagent.contracts.events.Topics;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.bus.Events;
import org.veriagent.core.ledger.InvoiceLedger;
import org.veriagent.core.state.InvoiceState;
import org.veriagent.core.testsupport.CoreTestApplication;
import org.veriagent.core.testsupport.Fixtures;
import org.veriagent.core.testsupport.StubProvider;
import org.veriagent.provider.mapping.InMemoryMappingProvider;
import org.veriagent.provider.mapping.MappingDocument;
import org.veriagent.provider.mapping.MappingProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = CoreTestApplication.class)
class ProviderAdapterTest {
    static final StubProvider ALFA = new StubProvider();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("veriagent.providers.endpoints.alfa.base-url", ALFA::baseUrl);
        r.add("veriagent.providers.endpoints.alfa.batch-size", () -> 5);
        r.add("veriagent.providers.endpoints.alfa.batch-window", () -> "200ms");
        r.add("veriagent.providers.seed-mappings.[alfa/CO]", () -> "agentic-plane/golden/mapping-alfa-v2.json");
    }

    @AfterAll
    static void close() { ALFA.close(); }

    @Autowired EventBus bus;
    @Autowired InvoiceLedger ledger;
    @Autowired ProviderAdapter adapter;
    @Autowired MappingProvider mappings;

    private InvoiceRequest registerAndRoute() {
        InvoiceRequest inv = Fixtures.coInvoice(Fixtures.number());
        ledger.register(inv, Payloads.Channel.BATCH, ContractJson.mapper().writeValueAsString(inv), "lote.json");
        bus.publish(Topics.providerRequest(inv.country(), "alfa"), Events.of(EventTypes.PROVIDER_REQUESTED, "CO",
            inv.invoiceKey(), "prueba", new Payloads.ProviderRequested("alfa", "R1@v1", inv)));
        return inv;
    }

    private void settle() {
        assertTrue(bus.awaitQuiescence(Duration.ofSeconds(10)));
        assertTrue(adapter.awaitIdle(Duration.ofSeconds(15)));
        assertTrue(bus.awaitQuiescence(Duration.ofSeconds(10)));
    }

    @Test
    @DisplayName("Comprobantes verificados se agrupan en lotes y pasan a SENT con el XML transformado y la versión de mapeo")
    void batchesAndSends() {
        ALFA.mode(StubProvider.Mode.OK);
        int before = ALFA.batches();
        List<InvoiceRequest> invs = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) invs.add(registerAndRoute());
        settle();
        for (InvoiceRequest inv : invs) {
            var r = ledger.find(inv.invoiceKey()).orElseThrow();
            assertEquals(InvoiceState.SENT, r.status(), r.invoiceKey());
            assertEquals("alfa", r.provider());
            assertEquals(1, r.mappingVersion());
            assertTrue(r.transformedPayload().contains("<ProfileID>PerfilAlfa-2.1</ProfileID>"));
        }
        assertEquals(2, ALFA.batches() - before, "7 comprobantes con lotes de 5: dos lotes");
    }

    @Test
    @DisplayName("Un mapeo defectuoso se bloquea antes de agrupar: FAILED con regla y norma, y respuesta al origen")
    void kitBlocksBeforeBatching() throws Exception {
        ALFA.mode(StubProvider.Mode.OK);
        var mem = (InMemoryMappingProvider) mappings;
        Path home = Path.of(System.getProperty("veriagent.home", "../..")).toAbsolutePath();
        ObjectNode bad = (ObjectNode) ContractJson.mapper().readTree(Files.readString(home.resolve("agentic-plane/golden/mapping-alfa-v2.json")));
        for (JsonNode e : bad.get("entries")) {
            if ("Invoice/ProfileID".equals(e.get("target").asString())) ((ObjectNode) e).put("value", "perfilalfa-2.1");
        }
        mem.publishAndActivate(MappingDocument.of("alfa", "CO", 2, bad));
        List<Payloads.InvoiceResponded> responses = new CopyOnWriteArrayList<>();
        try (var s = bus.subscribe(Topics.ALL_INVOICE_RESPONSES, "prueba-respuestas",
            (t, e) -> responses.add(Events.payload(e, Payloads.InvoiceResponded.class)))) {
            int batchesBefore = ALFA.batches();
            InvoiceRequest inv = registerAndRoute();
            settle();
            var r = ledger.find(inv.invoiceKey()).orElseThrow();
            assertEquals(InvoiceState.FAILED, r.status());
            assertEquals("KIT_BLOCKED", r.errorCode());
            assertTrue(r.findings().contains("R-D3-01"), r.findings());
            assertTrue(r.findings().contains("§2.1"));
            assertEquals(batchesBefore, ALFA.batches(), "no salió hacia el proveedor");
            assertEquals(1, responses.size());
            assertEquals("D3", responses.get(0).findings().get(0).type());
        } finally {
            mem.activate("alfa", "CO", 1);
        }
    }

    @Test
    @DisplayName("Proveedor caído: reintentos con espera creciente y, al agotarse, cada comprobante va a la DLQ")
    void providerDownGoesToDlq() {
        ALFA.mode(StubProvider.Mode.DOWN);
        List<Payloads.InvoiceDeadLettered> dlq = new CopyOnWriteArrayList<>();
        try (var s = bus.subscribe(Topics.ALL_INVOICE_DLQ, "prueba-dlq", (t, e) -> dlq.add(Events.payload(e, Payloads.InvoiceDeadLettered.class)))) {
            int callsBefore = ALFA.calls();
            InvoiceRequest a = registerAndRoute();
            InvoiceRequest b = registerAndRoute();
            settle();
            assertEquals(3, ALFA.calls() - callsBefore, "tres intentos (max-attempts=3) para el único lote");
            assertEquals(2, dlq.size());
            assertTrue(dlq.stream().allMatch(d -> d.category() == Payloads.ErrorCategory.RECOVERABLE && d.attempts() == 3 && "PROVIDER".equals(d.stage())));
            assertEquals(InvoiceState.PENDING_BATCH, ledger.find(a.invoiceKey()).orElseThrow().status(), "el dueño del estado DLQ es dlq-handler");
            assertNotNull(ledger.find(b.invoiceKey()).orElseThrow().transformedPayload());
        } finally {
            ALFA.mode(StubProvider.Mode.OK);
        }
    }
}
