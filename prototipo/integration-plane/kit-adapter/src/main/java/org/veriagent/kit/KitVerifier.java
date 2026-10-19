package org.veriagent.kit;

import org.tfg.kit.VerificationKit;
import org.tfg.kit.core.Finding;
import org.tfg.kit.core.Mode;
import org.tfg.kit.core.VerificationReport;
import org.veriagent.contracts.events.Payloads.KitFinding;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fachada del kit para el plano de integración. Registro de perfiles de verificación: cada perfil es un
 * formato de proveedor con su especificación, sus catálogos y su verificador. Los perfiles {@code alfa} (XML del
 * Perfil Alfa v2.1) y {@code beta} (segmentos) son los de la base, sin cambios de semántica.
 */
public class KitVerifier {

    /** Un perfil de verificación. */
    public interface Profile {
        String id();
        /** {@code xml}, {@code seg} o {@code json}. */
        String format();
        String specDocument();
        String specSha256();
        VerificationReport verify(String id, String content, Mode mode, String expected);
    }

    private final Map<String, Profile> profiles = new LinkedHashMap<>();

    public KitVerifier(VerificationKit kit) {
        register(new BaseProfile("alfa", "xml", kit));
        register(new BaseProfile("beta", "seg", kit));
    }

    public final void register(Profile p) {
        profiles.put(p.id(), p);
    }

    public Optional<Profile> profile(String id) {
        return Optional.ofNullable(profiles.get(id));
    }

    public List<Profile> profiles() {
        return List.copyOf(profiles.values());
    }

    /** Verifica un documento con el perfil indicado. */
    public KitResult verify(String profileId, String documentId, String content, Mode mode, String expected) {
        Profile p = profile(profileId).orElseThrow(() -> new IllegalArgumentException("Perfil de verificación desconocido: " + profileId));
        long t0 = System.nanoTime();
        VerificationReport r = p.verify(documentId, content, mode, expected);
        long micros = (System.nanoTime() - t0) / 1_000;
        return new KitResult(documentId, p.id(), mode.name(), r.passed(),
            r.findings().stream().map(KitVerifier::toFinding).toList(), p.specSha256(), micros);
    }

    static KitFinding toFinding(Finding f) {
        var pv = f.provenance();
        return new KitFinding(f.ruleId(), f.type().name(), f.location(), f.message(),
            pv == null ? null : pv.sourceDocument() + " " + pv.section(),
            pv == null ? null : pv.sourceSha256(),
            pv == null ? null : pv.ruleVersion());
    }

    /** Perfiles de la base: el kit original, sin cambios. */
    private record BaseProfile(String id, String format, VerificationKit kit) implements Profile {
        @Override
        public String specDocument() { return kit.spec().name(); }

        @Override
        public String specSha256() { return kit.spec().sha256(); }

        @Override
        public VerificationReport verify(String docId, String content, Mode mode, String expected) {
            return "seg".equals(format)
                ? kit.verifySegments(docId, content, mode, expected)
                : kit.verifyXml(docId, content, mode, expected);
        }
    }
}
