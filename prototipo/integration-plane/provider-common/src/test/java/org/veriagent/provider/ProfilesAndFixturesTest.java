package org.veriagent.provider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tfg.kit.VerificationKit;
import org.tfg.kit.core.Mode;
import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.model.Country;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.datagen.InvoiceGenerator;
import org.veriagent.datagen.Numbering;
import org.veriagent.kit.BetaPeProfile;
import org.veriagent.kit.GammaProfile;
import org.veriagent.kit.KitResult;
import org.veriagent.kit.KitVerifier;
import org.veriagent.provider.mapping.Renderer;
import org.veriagent.provider.mapping.Renderers;
import org.veriagent.provider.mapping.ValidatedFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Perfiles nuevos (beta-pe, gamma) y ejemplos validados. Con {@code -Dveriagent.regenerate-fixtures=true} se
 * regeneran los archivos de {@code data/fixtures} a partir de los mapeos de referencia aprobados.
 */
class ProfilesAndFixturesTest {
    static Path home;
    static KitVerifier kit;
    static Renderers renderers = new Renderers();
    static Numbering numbering;

    record Case(String provider, Country country, String mappingFile) {}

    static final List<Case> CASES = List.of(
        new Case("alfa", Country.CO, "agentic-plane/golden/mapping-alfa-v2.json"),
        new Case("beta", Country.PE, "data/mappings/beta-PE-v1.json"),
        new Case("gamma", Country.XX, null));

    @BeforeAll
    static void setUp() throws Exception {
        home = Path.of(System.getProperty("veriagent.home", "../..")).toAbsolutePath().normalize();
        kit = new KitVerifier(new VerificationKit(home));
        kit.register(new BetaPeProfile(home));
        kit.register(new GammaProfile(home));
        numbering = Numbering.load(home.resolve("data/numeracion"));
    }

    static JsonNode mapping(Case c) throws Exception {
        if (c.mappingFile() != null) return ContractJson.mapper().readTree(Files.readString(home.resolve(c.mappingFile())));
        try (InputStream in = ProfilesAndFixturesTest.class.getResourceAsStream("/reference/gamma-XX-reference.json")) {
            return ContractJson.mapper().readTree(in);
        }
    }

    @Test
    @DisplayName("Los ejemplos validados existen, pasan el kit y el mapeo de referencia los reproduce hoja por hoja")
    void fixturesAreConsistent() throws Exception {
        boolean regenerate = Boolean.getBoolean("veriagent.regenerate-fixtures");
        for (Case c : CASES) {
            JsonNode m = mapping(c);
            Renderer r = renderers.forProfile(m.get("profile").asString()).orElseThrow();
            if (regenerate || !ValidatedFixtures.exists(home, c.provider(), c.country().name())) {
                ArrayNode arr = ContractJson.mapper().createArrayNode();
                for (InvoiceRequest inv : InvoiceGenerator.fixtures(numbering, c.country())) {
                    ObjectNode f = arr.addObject();
                    f.set("canonical", ContractJson.mapper().valueToTree(inv));
                    f.put("expected", r.render(inv, m));
                }
                ObjectNode root = ContractJson.mapper().createObjectNode();
                root.put("provider", c.provider()).put("country", c.country().name()).put("kitProfile", r.kitProfile())
                    .put("note", "Ejemplos validados por persona: referencia del control de integración continua (ADR-05).");
                root.set("fixtures", arr);
                Path out = ValidatedFixtures.file(home, c.provider(), c.country().name());
                Files.createDirectories(out.getParent());
                Files.writeString(out, ContractJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(root));
            }
            var fixtures = ValidatedFixtures.load(home, c.provider(), c.country().name());
            assertTrue(fixtures.size() >= 6, c.toString());
            List<String> problems = new ArrayList<>();
            for (var f : fixtures) {
                KitResult rt = kit.verify(r.kitProfile(), "f", f.expected(), Mode.RUNTIME, null);
                KitResult ci = kit.verify(r.kitProfile(), "f", r.render(f.canonical(), m), Mode.CI, f.expected());
                if (!rt.passed() || !ci.passed()) problems.add(c + " " + f.canonical().documentNumber() + " " + rt.findings() + ci.findings());
            }
            assertEquals(List.of(), problems);
        }
    }

    @Test
    @DisplayName("beta-pe: catálogos de SUNAT; gamma: reglas D1 a D6 con la sección de la especificación y su huella")
    void newProfilesDetectDefects() throws Exception {
        var pe = ValidatedFixtures.load(home, "beta", "PE").get(0).expected();
        assertTrue(kit.verify("beta-pe", "x", pe, Mode.RUNTIME, null).passed());
        KitResult badPe = kit.verify("beta-pe", "x", pe.replaceFirst("\\|01\\|PEN\\|", "|91|PEN|"), Mode.RUNTIME, null);
        assertEquals("D1", badPe.findings().get(0).type());
        assertTrue(badPe.findings().get(0).source().startsWith("formato-beta-pe-v1.md §3.2"));

        String g = ValidatedFixtures.load(home, "gamma", "XX").get(0).expected();
        assertTrue(kit.verify("gamma", "x", g, Mode.RUNTIME, null).passed(), () -> kit.verify("gamma", "x", g, Mode.RUNTIME, null).findings().toString());
        record Mut(String type, String from, String to) {}
        for (Mut m : List.of(
            new Mut("D1", "\"documentType\" : \"FAC\"", "\"documentType\" : \"INV\""),
            new Mut("D3", "\"specVersion\" : \"GAMMA-1\"", "\"specVersion\" : \"gamma-1\""),
            new Mut("D4", "\"email\" : \"", "\"email\" : \"\" , \"x\" : \""),
            new Mut("D5", "\"specVersion\" : \"GAMMA-1\",", "\"specVersion\" : \"GAMMA-1\", \"legacyRef\" : \"R-1\","),
            new Mut("D6", "\"description\" : \"", "\"description\" : \"A | B "))) {
            String mutated = g.replaceFirst(java.util.regex.Pattern.quote(m.from()), java.util.regex.Matcher.quoteReplacement(m.to()));
            assertNotEquals(g, mutated, m.type());
            KitResult res = kit.verify("gamma", "x", mutated, Mode.RUNTIME, null);
            assertTrue(res.findings().stream().anyMatch(f -> f.type().equals(m.type())), m.type() + " -> " + res.findings());
            assertTrue(res.findings().stream().allMatch(f -> f.sourceSha256() != null && f.sourceSha256().length() == 64));
        }
        KitResult d2 = kit.verify("gamma", "x", g.replaceFirst("\"tax\" : \"([0-9.]+)\"", "\"tax\" : \"999.99\""), Mode.RUNTIME, null);
        assertTrue(d2.findings().stream().anyMatch(f -> f.type().equals("D2")), d2.findings()::toString);
    }
}
