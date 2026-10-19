package org.tfg.kit.core;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Resultado de verificar un comprobante. Aprobado solo si no hay hallazgos. */
public final class VerificationReport {
    private final String documentId;
    private final String mode;
    private final List<Finding> findings = new ArrayList<>();

    public VerificationReport(String documentId, String mode) { this.documentId = documentId; this.mode = mode; }
    public void add(Finding f) { findings.add(f); }
    public void addAll(List<Finding> fs) { findings.addAll(fs); }
    public List<Finding> findings() { return List.copyOf(findings); }
    public boolean passed() { return findings.isEmpty(); }
    public String documentId() { return documentId; }

    public String toJson() {
        return "{\"documentId\":" + Json.str(documentId) + ",\"mode\":" + Json.str(mode)
            + ",\"passed\":" + passed() + ",\"findings\":["
            + findings.stream().map(Finding::toJson).collect(Collectors.joining(",")) + "]}";
    }
}
