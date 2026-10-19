package org.veriagent.provider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tfg.kit.VerificationKit;
import org.tfg.kit.core.Mode;
import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.kit.KitResult;
import org.veriagent.kit.KitVerifier;
import org.veriagent.provider.mapping.AlfaXmlRenderer;
import org.veriagent.provider.mapping.BetaSegmentRenderer;
import org.veriagent.provider.mapping.InMemoryMappingProvider;
import org.veriagent.provider.mapping.MappingDocument;
import org.veriagent.provider.mapping.Renderers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El motor de mapeo Java produce lo mismo que el de Python (tfg_agents.mapping) con los mapeos golden: 30 casos
 * exportados desde Python (18 generados y los 12 fixtures curados). Así, lo que el kit evaluó en una propuesta
 * del agente es exactamente lo que el adaptador enviará.
 */
class RendererConformanceTest {
    static Path home;
    static KitVerifier kit;
    static JsonNode cases;
    static JsonNode alfaGolden;
    static JsonNode betaGolden;

    @BeforeAll
    static void setUp() throws Exception {
        home = Path.of(System.getProperty("veriagent.home", "../..")).toAbsolutePath().normalize();
        kit = new KitVerifier(new VerificationKit(home));
        try (InputStream in = RendererConformanceTest.class.getResourceAsStream("/conformance/cases.json")) {
            cases = ContractJson.mapper().readTree(in).get("cases");
        }
        alfaGolden = ContractJson.mapper().readTree(Files.readString(home.resolve("agentic-plane/golden/mapping-alfa-v2.json")));
        betaGolden = ContractJson.mapper().readTree(Files.readString(home.resolve("agentic-plane/golden/mapping-beta.json")));
    }

    private static InvoiceRequest invoice(JsonNode c) {
        return ContractJson.mapper().treeToValue(c.get("canonical"), InvoiceRequest.class);
    }

    @Test
    @DisplayName("Beta: el texto por segmentos de Java es idéntico, carácter a carácter, al de Python")
    void betaIsIdentical() {
        BetaSegmentRenderer r = new BetaSegmentRenderer();
        for (JsonNode c : cases) {
            assertEquals(c.get("beta").asString(), r.render(invoice(c), betaGolden), c.get("canonical").get("documentNumber").asString());
        }
    }

    @Test
    @DisplayName("Alfa: el XML de Java coincide hoja por hoja con el de Python (kit en modo CI) y pasa el kit en ejecución")
    void alfaMatchesPythonLeafByLeaf() {
        AlfaXmlRenderer r = new AlfaXmlRenderer();
        List<String> failures = new ArrayList<>();
        for (JsonNode c : cases) {
            String java = r.render(invoice(c), alfaGolden);
            KitResult ci = kit.verify("alfa", "c", java, Mode.CI, c.get("alfa").asString());
            KitResult rt = kit.verify("alfa", "c", java, Mode.RUNTIME, null);
            if (!ci.passed() || !rt.passed()) failures.add(c.get("canonical").get("documentNumber").asString() + " " + ci.findings() + rt.findings());
        }
        assertEquals(List.of(), failures);
        assertEquals(30, cases.size());
    }

    @Test
    @DisplayName("El preparador aplica el mapeo vigente y bloquea con el kit un mapeo defectuoso (D1)")
    void preparerBlocksDefectiveMapping() {
        InMemoryMappingProvider mappings = new InMemoryMappingProvider();
        mappings.publishAndActivate(MappingDocument.of("alfa", "CO", 1, alfaGolden));
        DocumentPreparer preparer = new DocumentPreparer(mappings, new Renderers(), kit);
        InvoiceRequest inv = invoice(cases.get(0));
        var ok = preparer.prepare(inv, "alfa");
        assertTrue(ok.passed(), ok.verification().findings()::toString);
        assertEquals(1, ok.mappingVersion());

        ObjectNode bad = (ObjectNode) alfaGolden.deepCopy();
        for (JsonNode e : bad.get("entries")) {
            if ("Invoice/InvoiceTypeCode".equals(e.get("target").asString())) ((ObjectNode) e.get("table")).put("INVOICE", "07");
        }
        mappings.publishAndActivate(MappingDocument.of("alfa", "CO", 2, bad));
        InvoiceRequest anInvoice = java.util.stream.StreamSupport.stream(cases.spliterator(), false).map(RendererConformanceTest::invoice)
            .filter(i -> i.documentType().name().equals("INVOICE")).findFirst().orElseThrow();
        var blocked = preparer.prepare(anInvoice, "alfa");
        assertFalse(blocked.passed());
        assertEquals(2, blocked.mappingVersion());
        assertEquals("D1", blocked.verification().findings().get(0).type());
        assertNotNull(blocked.verification().findings().get(0).sourceSha256());

        mappings.activate("alfa", "CO", 1);
        assertTrue(preparer.prepare(anInvoice, "alfa").passed(), "tras revertir a la versión 1 vuelve a pasar");
        assertThrows(DocumentPreparer.MissingMappingException.class, () -> preparer.prepare(inv, "beta"));
    }
}
