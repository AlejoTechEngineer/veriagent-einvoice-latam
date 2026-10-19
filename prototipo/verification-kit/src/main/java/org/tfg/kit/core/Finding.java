package org.tfg.kit.core;

/** Hallazgo de verificación con la regla que lo produjo y su procedencia normativa. */
public record Finding(String ruleId, DefectType type, String location, String message, Provenance provenance) {
    public String toJson() {
        return "{\"ruleId\":" + Json.str(ruleId) + ",\"type\":" + Json.str(type.name())
            + ",\"location\":" + Json.str(location) + ",\"message\":" + Json.str(message)
            + ",\"source\":" + Json.str(provenance == null ? "" : provenance.sourceDocument() + " " + provenance.section())
            + ",\"sourceSha256\":" + Json.str(provenance == null ? "" : provenance.sourceSha256())
            + ",\"ruleVersion\":" + Json.str(provenance == null ? "" : provenance.ruleVersion()) + "}";
    }
}
