package org.veriagent.mappingregistry;

import org.tfg.kit.core.Mode;
import org.veriagent.contracts.events.Payloads.KitFinding;
import org.veriagent.contracts.model.Country;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.datagen.InvoiceGenerator;
import org.veriagent.datagen.Numbering;
import org.veriagent.kit.KitResult;
import org.veriagent.kit.KitVerifier;
import org.veriagent.provider.mapping.Renderer;
import org.veriagent.provider.mapping.Renderers;
import org.veriagent.provider.mapping.ValidatedFixtures;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Control obligatorio del kit sobre un mapeo candidato (ADR-05), el mismo que corre en el pipeline:
 * <ol>
 *   <li>kit en modo ejecución sobre una muestra de comprobantes sintéticos del país (semilla fija);</li>
 *   <li>kit en modo integración continua sobre los ejemplos validados por personas del proveedor y el país.</li>
 * </ol>
 * Un mapeo que no pasa ambos queda bloqueado y no se puede aprobar.
 */
public class MappingGate {
    static final int SAMPLE = 50;
    static final long SAMPLE_SEED = 99;

    private final KitVerifier kit;
    private final Renderers renderers;
    private final Path home;
    private final Numbering numbering;

    public MappingGate(KitVerifier kit, Renderers renderers, Path home) {
        this.kit = kit;
        this.renderers = renderers;
        this.home = home;
        this.numbering = Numbering.load(home.resolve("data/numeracion"));
    }

    public record Result(boolean passed, String kitProfile, int runtimeDocuments, int runtimeFailed, int ciDocuments,
                         int ciFailed, Map<String, Integer> findingsByRule, Map<String, Integer> findingsByType,
                         List<KitFinding> examples, String problem) {}

    public Result verify(String provider, Country country, JsonNode mapping) {
        String profile = mapping.has("profile") ? mapping.get("profile").asString() : null;
        Renderer r = profile == null ? null : renderers.forProfile(profile).orElse(null);
        if (r == null) return failed(null, "Perfil de mapeo desconocido: " + profile);
        if (!ValidatedFixtures.exists(home, provider, country.name())) {
            return failed(r.kitProfile(), "No hay ejemplos validados para " + provider + "/" + country + ": no se puede aprobar ningún mapeo");
        }
        Map<String, Integer> byRule = new LinkedHashMap<>(), byType = new LinkedHashMap<>();
        List<KitFinding> examples = new ArrayList<>();
        int rtFailed = 0, ciFailed = 0;
        List<InvoiceRequest> sample = new InvoiceGenerator(numbering, SAMPLE_SEED, 700_000).generate(country, SAMPLE);
        for (InvoiceRequest inv : sample) {
            KitResult v = verifyOne(r, inv, mapping, Mode.RUNTIME, null);
            if (!v.passed()) { rtFailed++; collect(v, byRule, byType, examples); }
        }
        var fixtures = ValidatedFixtures.load(home, provider, country.name());
        for (var f : fixtures) {
            KitResult v = verifyOne(r, f.canonical(), mapping, Mode.CI, f.expected());
            if (!v.passed()) { ciFailed++; collect(v, byRule, byType, examples); }
        }
        boolean ok = rtFailed == 0 && ciFailed == 0;
        return new Result(ok, r.kitProfile(), sample.size(), rtFailed, fixtures.size(), ciFailed, byRule, byType,
            examples.size() > 8 ? examples.subList(0, 8) : examples, ok ? null : "El mapeo no pasó el kit de verificación");
    }

    private KitResult verifyOne(Renderer r, InvoiceRequest inv, JsonNode mapping, Mode mode, String expected) {
        String doc;
        try {
            doc = r.render(inv, mapping);
        } catch (RuntimeException e) {
            return new KitResult(inv.invoiceKey(), r.kitProfile(), mode.name(), false,
                List.of(new KitFinding("MAP-E0-01", "E0", "mapeo", "El mapeo no se pudo aplicar: " + e.getMessage(), null, null, null)), null, 0);
        }
        return kit.verify(r.kitProfile(), inv.invoiceKey(), doc, mode, expected);
    }

    private static void collect(KitResult v, Map<String, Integer> byRule, Map<String, Integer> byType, List<KitFinding> examples) {
        for (KitFinding f : v.findings()) {
            byRule.merge(f.ruleId(), 1, Integer::sum);
            byType.merge(f.type(), 1, Integer::sum);
            if (examples.stream().noneMatch(x -> x.ruleId().equals(f.ruleId()))) examples.add(f);
        }
    }

    private static Result failed(String kitProfile, String problem) {
        return new Result(false, kitProfile, 0, 0, 0, 0, Map.of(), Map.of(), List.of(), problem);
    }
}
