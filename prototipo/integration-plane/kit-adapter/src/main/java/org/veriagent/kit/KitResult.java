package org.veriagent.kit;

import org.veriagent.contracts.events.Payloads.KitFinding;

import java.util.List;

/**
 * Resultado de verificar un documento: aprobado solo si no hay hallazgos. Cada hallazgo lleva la regla, el
 * tipo D1 a D6, la sección de la norma y la huella digital (SHA-256) del documento normativo.
 */
public record KitResult(String documentId, String profile, String mode, boolean passed, List<KitFinding> findings,
                        String specSha256, long durationMicros) {
}
