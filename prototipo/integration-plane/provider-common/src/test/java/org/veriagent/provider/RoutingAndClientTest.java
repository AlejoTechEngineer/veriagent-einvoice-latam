package org.veriagent.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.provider.client.ProviderClient;
import org.veriagent.provider.client.ProviderProtocol;
import org.veriagent.provider.client.ProvidersProperties;
import org.veriagent.provider.routing.RoutingTable;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class RoutingAndClientTest {

    private static InvoiceRequest invoice(String country, String docType) throws Exception {
        try (InputStream in = RoutingAndClientTest.class.getResourceAsStream("/conformance/cases.json")) {
            ObjectNode c = (ObjectNode) ContractJson.mapper().readTree(in).get("cases").get(0).get("canonical").deepCopy();
            c.put("country", country).put("documentType", docType);
            return ContractJson.mapper().treeToValue(c, InvoiceRequest.class);
        }
    }

    @Test
    @DisplayName("La tabla de enrutamiento versionada elige el proveedor por país y tipo de documento")
    void routing() throws Exception {
        Path home = Path.of(System.getProperty("veriagent.home", "../..")).toAbsolutePath().normalize();
        RoutingTable t = RoutingTable.load(home.resolve("data/routing/routing-table.v1.json"));
        assertEquals(1, t.version());
        assertEquals(64, t.sha256().length());
        assertEquals("alfa", t.route(invoice("CO", "INVOICE")).orElseThrow().provider());
        assertEquals("beta", t.route(invoice("PE", "CREDIT_NOTE")).orElseThrow().provider());
        assertEquals("gamma", t.route(invoice("XX", "INVOICE")).orElseThrow().provider());
        RoutingTable byType = new RoutingTable(2, List.of(
            new RoutingTable.Rule("A", "CO", "DEBIT_NOTE", "beta"), new RoutingTable.Rule("B", "CO", "*", "alfa")), "x");
        assertEquals("beta", byType.route(invoice("CO", "DEBIT_NOTE")).orElseThrow().provider());
        assertEquals("B", byType.route(invoice("CO", "INVOICE")).orElseThrow().ruleId());
        assertTrue(byType.route(invoice("PE", "INVOICE")).isEmpty());
    }

    @Test
    @DisplayName("Cliente: decisión síncrona, 503 recuperable y lote ZIP con manifiesto")
    void client() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[][] lastZip = new byte[1][];
        server.createContext("/api/v1/documents", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean down = body.contains("CAIDO");
            byte[] resp = ("{\"documentId\":\"K1\",\"status\":\"AUTHORIZED\",\"code\":\"00\",\"message\":\"ok\","
                + "\"authorizationNumber\":\"AUT-1\",\"receiptSha256\":null}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(down ? 503 : 200, down ? -1 : resp.length);
            if (!down) ex.getResponseBody().write(resp);
            ex.close();
        });
        server.createContext("/api/v1/batches", ex -> {
            lastZip[0] = ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(202, -1);
            ex.close();
        });
        server.start();
        try {
            ProvidersProperties props = new ProvidersProperties();
            ProvidersProperties.Provider p = new ProvidersProperties.Provider();
            p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            props.getEndpoints().put("alfa", p);
            ProviderClient client = new ProviderClient(props, Files.createTempDirectory("sftp"));
            var d = client.submit("alfa", "K1", "xml", "<Invoice/>", Map.of());
            assertTrue(d.authorized());
            assertEquals("AUT-1", d.authorizationNumber());
            assertThrows(ProviderClient.ProviderUnavailableException.class, () -> client.submit("alfa", "K2", "xml", "CAIDO", Map.of()));

            var manifest = new ProviderProtocol.BatchManifest("lote-1", "alfa", "CO",
                List.of(new ProviderProtocol.ManifestEntry(0, "0.xml", "K1"), new ProviderProtocol.ManifestEntry(1, "1.xml", "K2")));
            client.submitBatch("alfa", manifest, Map.of("0.xml", "<a/>", "1.xml", "<b/>"));
            String multipart = new String(lastZip[0], StandardCharsets.ISO_8859_1);
            int start = multipart.indexOf("PK");
            int end = multipart.lastIndexOf("\r\n--");
            try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(lastZip[0], start, end - start))) {
                assertEquals("manifest.json", z.getNextEntry().getName());
                assertEquals("0.xml", z.getNextEntry().getName());
                assertEquals("1.xml", z.getNextEntry().getName());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Cliente: el lote de Beta se deposita en la carpeta SFTP con manifiesto, sin archivos a medias")
    void folderBatch() throws Exception {
        Path sftp = Files.createTempDirectory("sftp");
        ProvidersProperties props = new ProvidersProperties();
        ProvidersProperties.Provider p = new ProvidersProperties.Provider();
        p.setBatchMode("sftp-folder");
        props.getEndpoints().put("beta", p);
        var manifest = new ProviderProtocol.BatchManifest("../../lote-9", "beta", "PE",
            List.of(new ProviderProtocol.ManifestEntry(0, "0.seg", "K1")));
        new ProviderClient(props, sftp).submitBatch("beta", manifest, Map.of("0.seg", "ENC|1\n"));
        Path inbox = sftp.resolve("outbound/beta/inbox");
        assertTrue(Files.exists(inbox.resolve("lote-9.seg")), "el nombre se sanea: sin recorrido de rutas");
        assertTrue(Files.exists(inbox.resolve("lote-9.manifest.json")));
        try (var s = Files.list(inbox)) {
            assertTrue(s.noneMatch(f -> f.toString().endsWith(".part")));
        }
    }
}
