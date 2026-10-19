package org.tfg.kit.core;

/** Procedencia de una regla: documento normativo, sección, hash del documento y versión de la regla. */
public record Provenance(String sourceDocument, String section, String sourceSha256, String ruleVersion) {
    public static Provenance of(SpecDocument spec, String section, String ruleVersion) {
        return new Provenance(spec.name(), section, spec.sha256(), ruleVersion);
    }
}
