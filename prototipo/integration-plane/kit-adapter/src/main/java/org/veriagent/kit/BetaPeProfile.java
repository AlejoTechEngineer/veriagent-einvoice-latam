package org.veriagent.kit;

import org.tfg.kit.catalog.CatalogRegistry;
import org.tfg.kit.core.FixtureComparator;
import org.tfg.kit.core.Mode;
import org.tfg.kit.core.Provenance;
import org.tfg.kit.core.SpecDocument;
import org.tfg.kit.core.VerificationReport;
import org.tfg.kit.segment.BetaVerifier;

import java.nio.file.Path;
import java.util.List;

/**
 * Perfil {@code beta-pe}: el formato por segmentos de Beta con los catálogos de Perú (códigos públicos de SUNAT).
 * Reutiliza sin cambios el verificador de segmentos del kit con otra especificación y otros catálogos.
 */
public final class BetaPeProfile implements KitVerifier.Profile {
    private final SpecDocument spec;
    private final BetaVerifier beta;

    public BetaPeProfile(Path home) throws Exception {
        this.spec = SpecDocument.load(home.resolve("specs/formato-beta-pe-v1.md"));
        this.beta = new BetaVerifier(CatalogRegistry.load(home.resolve("catalogs/catalogos-beta-pe-v1.csv")), spec);
    }

    @Override public String id() { return "beta-pe"; }
    @Override public String format() { return "seg"; }
    @Override public String specDocument() { return spec.name(); }
    @Override public String specSha256() { return spec.sha256(); }

    @Override
    public VerificationReport verify(String id, String content, Mode mode, String expected) {
        VerificationReport r = new VerificationReport(id, mode.name());
        r.addAll(beta.structure(content));
        if (mode == Mode.SCHEMA_ONLY) return r;
        r.addAll(beta.rules(content));
        if (mode == Mode.CI && expected != null) {
            r.addAll(FixtureComparator.compare(List.of(expected.strip().split("\\R")), List.of(content.strip().split("\\R")),
                Provenance.of(spec, "fixture validado", "1.0.0")));
        }
        return r;
    }
}
