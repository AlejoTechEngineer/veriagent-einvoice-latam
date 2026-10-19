package org.veriagent.simulators;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.model.Country;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.core.VeriagentProperties;
import org.veriagent.core.io.SafeFiles;
import org.veriagent.core.security.DemoTokenStore;
import org.veriagent.core.testsupport.CoreTestApplication;
import org.veriagent.core.testsupport.Fixtures;
import org.veriagent.core.testsupport.TestTokens;
import org.veriagent.datagen.InvoiceGenerator;
import org.veriagent.datagen.Numbering;
import org.veriagent.provider.DocumentPreparer;
import org.veriagent.provider.client.ProviderClient;
import org.veriagent.provider.client.ProviderProtocol;
import org.veriagent.provider.client.ProviderProtocol.BatchManifest;
import org.veriagent.provider.client.ProviderProtocol.BatchResult;
import org.veriagent.provider.client.ProviderProtocol.ManifestEntry;
import org.veriagent.provider.client.ProvidersProperties;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = CoreTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProviderSimulatorTest {
    @LocalServerPort int port;
    @Autowired DocumentPreparer preparer;
    @Autowired ProvidersProperties providers;
    @Autowired ProviderSimulator sim;
    @Autowired DemoTokenStore store;
    @Autowired VeriagentProperties core;
    ProviderClient client;
    Path returnRoot;
    TestTokens tokens;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        tokens = new TestTokens(store);
        for (String p : List.of("alfa", "beta")) {
            var e = new ProvidersProperties.Provider();
            e.setBaseUrl("http://127.0.0.1:" + port + "/sim/" + p);
            e.setSyncFormat("alfa".equals(p) ? "json-envelope" : "text");
            e.setBatchMode("alfa".equals(p) ? "http-zip" : "sftp-folder");
            e.setTimeout(Duration.ofSeconds(1));
            providers.getEndpoints().put(p, e);
        }
        Path sftp = SimulatorConfiguration.sftpRoot(core, providers);
        client = new ProviderClient(providers, sftp);
        returnRoot = sftp.resolve("return");
        for (String p : List.of("alfa", "beta")) sim.faults().clear(p);
    }

    private DocumentPreparer.Prepared alfa() {
        var d = preparer.prepare(Fixtures.coInvoice(Fixtures.number()), "alfa");
        assertTrue(d.passed(), d.verification().findings().toString());
        return d;
    }

    private Path receipt(String provider, String key) {
        return returnRoot.resolve(provider).resolve("receipts").resolve(SafeFiles.sanitize(key) + ".receipt.json");
    }

    private static Path waitFor(Path file) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (!Files.exists(file) && System.currentTimeMillis() < end) Thread.sleep(50);
        assertTrue(Files.exists(file), "no apareció " + file);
        return file;
    }

    private int post(String path, String token, String json) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json));
        if (token != null) b.header("Authorization", token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    @DisplayName("Alfa síncrono: autoriza de forma determinista, deja la constancia y rechaza un documento inválido")
    void alfaSync() {
        var doc = alfa();
        String key = doc.verification().documentId();
        var d1 = client.submit("alfa", key, "xml", doc.content(), Map.of());
        var d2 = client.submit("alfa", key, "xml", doc.content(), Map.of());
        assertTrue(d1.authorized(), d1.toString());
        assertEquals(d1.authorizationNumber(), d2.authorizationNumber());
        assertTrue(Files.exists(receipt("alfa", key)));
        var bad = client.submit("alfa", key, "xml", doc.content().substring(0, doc.content().length() / 2), Map.of());
        assertFalse(bad.authorized(), bad.toString());
        assertTrue(bad.code().startsWith("VAL-"), bad.code());
    }

    @Test
    @DisplayName("Fallos por cabecera: no disponible y lento son recuperables; respuesta perdida deja solo la constancia")
    void headerFaults() {
        var doc = alfa();
        String key = doc.verification().documentId();
        assertThrows(ProviderClient.ProviderUnavailableException.class,
            () -> client.submit("alfa", key, "xml", doc.content(), Map.of(ProviderProtocol.FAULT_HEADER, "unavailable")));
        assertThrows(ProviderClient.ProviderUnavailableException.class,
            () -> client.submit("alfa", key, "xml", doc.content(), Map.of(ProviderProtocol.FAULT_HEADER, "slow")));
        assertFalse(Files.exists(receipt("alfa", key)));
        assertThrows(ProviderClient.ProviderUnavailableException.class,
            () -> client.submit("alfa", key, "xml", doc.content(), Map.of(ProviderProtocol.FAULT_HEADER, "lost-response")));
        assertTrue(Files.exists(receipt("alfa", key)));
    }

    @Test
    @DisplayName("Administración de fallos: sin token 401, agente y revisor 403, el operador activa un rechazo de catálogo de una vez")
    void adminFaults() throws Exception {
        String body = "{\"fault\":\"reject-catalog\",\"remaining\":1}";
        assertEquals(401, post("/api/simulators/alfa/faults", null, body));
        assertEquals(403, post("/api/simulators/alfa/faults", tokens.agent(), body));
        assertEquals(403, post("/api/simulators/alfa/faults", tokens.reviewer(), body));
        assertEquals(200, post("/api/simulators/alfa/faults", tokens.operator(), body));
        var doc = alfa();
        String key = doc.verification().documentId();
        assertEquals("CAT-01", client.submit("alfa", key, "xml", doc.content(), Map.of()).code());
        assertTrue(client.submit("alfa", key, "xml", doc.content(), Map.of()).authorized(), "el fallo era de una sola llamada");
    }

    @Test
    @DisplayName("Alfa por lotes (ZIP) y Beta por carpeta SFTP: los resultados llegan a la carpeta de retorno")
    void batches() throws Exception {
        List<ManifestEntry> entries = new ArrayList<>();
        Map<String, String> content = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            var d = alfa();
            String name = "doc-" + i + ".xml";
            entries.add(new ManifestEntry(i, name, d.verification().documentId()));
            content.put(name, d.content());
        }
        client.submitBatch("alfa", new BatchManifest("lote-alfa-1", "alfa", "CO", entries), content);
        var ra = ContractJson.mapper().readValue(Files.readString(waitFor(returnRoot.resolve("alfa/lote-alfa-1.result.json"))), BatchResult.class);
        assertEquals(5, ra.results().stream().filter(ProviderProtocol.Decision::authorized).count(), ra.toString());

        var gen = new InvoiceGenerator(Numbering.load(Path.of(core.getHome()).resolve("data/numeracion")), 7, 900_000);
        List<ManifestEntry> be = new ArrayList<>();
        Map<String, String> bc = new LinkedHashMap<>();
        int i = 0;
        for (InvoiceRequest inv : gen.generate(Country.PE, 4)) {
            var d = preparer.prepare(inv, "beta");
            assertTrue(d.passed(), d.verification().findings().toString());
            String name = "doc-" + i + ".seg";
            be.add(new ManifestEntry(i++, name, inv.invoiceKey()));
            bc.put(name, d.content());
        }
        client.submitBatch("beta", new BatchManifest("lote-beta-1", "beta", "PE", be), bc);
        var rb = ContractJson.mapper().readValue(Files.readString(waitFor(returnRoot.resolve("beta/lote-beta-1.result.json"))), BatchResult.class);
        assertEquals(4, rb.results().stream().filter(ProviderProtocol.Decision::authorized).count(), rb.toString());
    }

    @Test
    @DisplayName("Los lotes ZIP con rutas en los nombres de entrada se rechazan (path traversal)")
    void zipTraversal() throws Exception {
        var bos = new ByteArrayOutputStream();
        try (var z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry("../fuera.txt"));
            z.write("x".getBytes());
            z.closeEntry();
        }
        assertThrows(IllegalArgumentException.class, () -> SimulatorController.unzip(bos.toByteArray(), 1000));
    }
}
