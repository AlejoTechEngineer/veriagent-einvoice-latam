package org.veriagent.ingestsftp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.events.Topics;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.dlq.DlqRepository;
import org.veriagent.core.ledger.InvoiceLedger;
import org.veriagent.core.security.DemoTokenStore;
import org.veriagent.core.testsupport.CoreTestApplication;
import org.veriagent.core.testsupport.Fixtures;
import org.veriagent.core.testsupport.TestTokens;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CoreTestApplication.class, properties = "veriagent.ingest.poller-enabled=false")
@AutoConfigureMockMvc
class IngestTest {
    @Autowired IngestService service;
    @Autowired InvoiceLedger ledger;
    @Autowired DlqRepository dlq;
    @Autowired EventBus bus;
    @Autowired MockMvc mvc;
    @Autowired DemoTokenStore store;
    TestTokens tokens;

    @BeforeEach
    void setUp() { tokens = new TestTokens(store); }

    private static byte[] json(List<InvoiceRequest> invs) {
        ArrayNode a = ContractJson.mapper().createArrayNode();
        invs.forEach(i -> a.add(ContractJson.mapper().valueToTree(i)));
        return ContractJson.mapper().writeValueAsBytes(a);
    }

    @Test
    @DisplayName("Un lote válido se registra y publica; un reenvío del mismo lote no duplica nada")
    void ingestsAndIsIdempotent() {
        List<String> topics = new CopyOnWriteArrayList<>();
        try (var s = bus.subscribe(Topics.ALL_INVOICE_REQUESTS, "prueba-ingesta", (t, e) -> topics.add(t))) {
            var invs = List.of(Fixtures.coInvoice(Fixtures.number()), Fixtures.coInvoice(Fixtures.number()), Fixtures.coInvoice(Fixtures.number()));
            var first = service.ingest("lote-a.json", json(invs));
            var again = service.ingest("lote-a.json", json(invs));
            assertEquals(new IngestService.Summary("lote-a.json", first.sha256(), 3, 3, 0, 0), first);
            assertEquals(3, again.duplicates());
            assertEquals(0, again.accepted());
            assertTrue(bus.awaitQuiescence(Duration.ofSeconds(5)));
            assertEquals(3, topics.size());
            assertTrue(topics.stream().allMatch("co.invoice.request.v1"::equals));
            assertEquals("lote-a.json", ledger.find(invs.get(0).invoiceKey()).orElseThrow().sourceFile());
        }
    }

    @Test
    @DisplayName("Lo inválido (esquema o invariantes) va a la DLQ sin registrarse; lo válido del mismo lote sigue")
    void invalidItemsGoToDlq() {
        InvoiceRequest ok = Fixtures.coInvoice(Fixtures.number());
        ObjectNode badSchema = ContractJson.mapper().valueToTree(Fixtures.coInvoice(Fixtures.number()));
        badSchema.remove("supplier");
        ObjectNode badTotals = ContractJson.mapper().valueToTree(Fixtures.coInvoice(Fixtures.number()));
        ((ObjectNode) badTotals.get("totals")).put("taxAmount", 99);
        ArrayNode a = ContractJson.mapper().createArrayNode().add(ContractJson.mapper().valueToTree(ok)).add(badSchema).add(badTotals);
        long before = dlq.countOpenWithoutInvoice();
        var s = service.ingest("lote-b.json", ContractJson.mapper().writeValueAsBytes(a));
        assertEquals(1, s.accepted());
        assertEquals(2, s.rejected());
        assertEquals(before + 2, dlq.countOpenWithoutInvoice());
        var entries = dlq.list(DlqRepository.Status.OPEN, 10);
        assertTrue(entries.stream().anyMatch(e -> e.sourceRef().equals("lote-b.json#2") && e.lastError().contains("esquema")));
        assertTrue(entries.stream().anyMatch(e -> e.sourceRef().equals("lote-b.json#3") && e.lastError().contains("impuesto")));
    }

    @Test
    @DisplayName("ZIP: se procesan los JSON y se rechazan las rutas con recorrido y las bombas de descompresión")
    void zipLimits() throws Exception {
        ByteArrayOutputStream ok = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(ok)) {
            z.putNextEntry(new ZipEntry("parte1.json"));
            z.write(json(List.of(Fixtures.coInvoice(Fixtures.number()))));
            z.putNextEntry(new ZipEntry("leeme.txt"));
            z.write("ignorado".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(1, service.ingest("lote.zip", ok.toByteArray()).accepted());

        ByteArrayOutputStream slip = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(slip)) {
            z.putNextEntry(new ZipEntry("../../fuera.json"));
            z.write("[]".getBytes(StandardCharsets.UTF_8));
        }
        assertThrows(IngestService.RejectedFileException.class, () -> service.ingest("slip.zip", slip.toByteArray()));

        ByteArrayOutputStream bomb = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bomb)) {
            z.putNextEntry(new ZipEntry("bomba.json"));
            byte[] zeros = new byte[1024 * 1024];
            for (int i = 0; i < 6; i++) z.write(zeros);
        }
        assertTrue(bomb.size() < 100_000, "el ZIP comprimido es pequeño");
        assertThrows(IngestService.RejectedFileException.class, () -> service.ingest("bomba.zip", bomb.toByteArray()));
        assertThrows(IngestService.RejectedFileException.class, () -> service.ingest("programa.exe", new byte[]{1}));
    }

    @Test
    @DisplayName("API: solo OPERATOR o SYSTEM cargan lotes; el agente recibe 403 y sin token 401")
    void uploadApi() throws Exception {
        var file = new MockMultipartFile("file", "../../lote api.json", "application/json", json(List.of(Fixtures.coInvoice(Fixtures.number()))));
        mvc.perform(multipart("/api/ingest/batches").file(file)).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/ingest/batches").file(file).header("Authorization", tokens.agent())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/ingest/batches").file(file).header("Authorization", tokens.operator()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.file").value("lote_api.json")).andExpect(jsonPath("$.accepted").value(1));
    }
}
