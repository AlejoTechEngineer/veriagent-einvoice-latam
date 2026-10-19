package org.veriagent.platform;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.core.VeriagentProperties;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.ledger.InvoiceLedger;
import org.veriagent.core.security.DemoTokenStore;
import org.veriagent.core.state.InvoiceState;
import org.veriagent.core.testsupport.Fixtures;
import org.veriagent.core.testsupport.StubProvider;
import org.veriagent.core.testsupport.TestTokens;
import org.veriagent.legalarchive.LegalArchive;
import org.veriagent.provider.client.ProviderProtocol;
import org.veriagent.provider.client.ProvidersProperties;
import org.veriagent.provideradapter.ProviderAdapter;
import tools.jackson.databind.node.ArrayNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flujo asíncrono completo dentro de la aplicación: carga del lote, ingesta, enrutador, adaptador (kit antes de
 * agrupar), envío del lote, resultado en la carpeta de retorno, respuesta, publicación al origen y archivo legal.
 * El proveedor es un stub mínimo; los simuladores completos llegan en la Fase 3.
 */
@SpringBootTest(properties = "veriagent.ingest.poller-enabled=false")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class BatchFlowTest {
    static final StubProvider ALFA = new StubProvider();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("veriagent.providers.endpoints.alfa.base-url", ALFA::baseUrl);
        r.add("veriagent.providers.endpoints.alfa.batch-size", () -> 50);
        r.add("veriagent.providers.endpoints.alfa.batch-window", () -> "200ms");
    }

    @AfterAll
    static void close() { ALFA.close(); }

    @Autowired MockMvc mvc;
    @Autowired EventBus bus;
    @Autowired InvoiceLedger ledger;
    @Autowired ProviderAdapter adapter;
    @Autowired LegalArchive archive;
    @Autowired DemoTokenStore store;
    @Autowired VeriagentProperties core;
    @Autowired ProvidersProperties providers;

    @Test
    @DisplayName("Lote de 20 comprobantes: todos autorizados, publicados al origen y archivados con cadena válida")
    void fullBatchFlow() throws Exception {
        List<InvoiceRequest> invs = new ArrayList<>();
        ArrayNode arr = ContractJson.mapper().createArrayNode();
        for (int i = 0; i < 20; i++) {
            InvoiceRequest inv = Fixtures.coInvoice(Fixtures.number());
            invs.add(inv);
            arr.add(ContractJson.mapper().valueToTree(inv));
        }
        TestTokens tokens = new TestTokens(store);
        mvc.perform(multipart("/api/ingest/batches").file(new MockMultipartFile("file", "lote-e2e.json", "application/json",
                ContractJson.mapper().writeValueAsBytes(arr))).header("Authorization", tokens.operator()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.accepted").value(20));
        assertTrue(bus.awaitQuiescence(Duration.ofSeconds(15)));
        assertTrue(adapter.awaitIdle(Duration.ofSeconds(15)));
        assertTrue(bus.awaitQuiescence(Duration.ofSeconds(15)));
        for (InvoiceRequest inv : invs) assertEquals(InvoiceState.SENT, ledger.find(inv.invoiceKey()).orElseThrow().status());
        assertTrue(ALFA.batches() >= 1);

        // El proveedor deja el resultado del lote en la carpeta de retorno (fuente primaria).
        List<ProviderProtocol.Decision> ds = new ArrayList<>();
        for (int i = 0; i < invs.size(); i++) {
            ds.add(new ProviderProtocol.Decision(invs.get(i).invoiceKey(), "AUTHORIZED", "00", "Autorizado", "AUT-E2E-" + i, null));
        }
        Path ret = Path.of(core.getHome()).resolve(providers.getSftpRoot()).resolve("return").resolve("alfa").toAbsolutePath();
        Files.createDirectories(ret);
        Files.writeString(ret.resolve("lote-e2e.result.json"),
            ContractJson.mapper().writeValueAsString(new ProviderProtocol.BatchResult("lote-e2e", "alfa", ds)));
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline
            && invs.stream().anyMatch(i -> ledger.find(i.invoiceKey()).orElseThrow().status() != InvoiceState.AUTHORIZED)) {
            Thread.sleep(100);
        }
        assertTrue(bus.awaitQuiescence(Duration.ofSeconds(15)));
        for (InvoiceRequest inv : invs) assertEquals(InvoiceState.AUTHORIZED, ledger.find(inv.invoiceKey()).orElseThrow().status());
        long archived = invs.stream().filter(i -> archive.find(i.invoiceKey()).isPresent()).count();
        assertEquals(20, archived);
        assertTrue(archive.verify().valid());
        Path published = Path.of(core.getHome()).resolve(providers.getSftpRoot()).resolve("responses/co/lote-e2e.json.respuestas.jsonl").toAbsolutePath();
        assertEquals(20, Files.readAllLines(published).size());
        mvc.perform(get("/api/invoices/stats").header("Authorization", tokens.reviewer()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.conservation.balanced").value(true));
    }
}
