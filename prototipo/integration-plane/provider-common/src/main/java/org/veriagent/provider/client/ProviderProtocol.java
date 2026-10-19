package org.veriagent.provider.client;

import java.util.List;

/**
 * Contrato entre la plataforma y los proveedores (simulados). Lo implementan los simuladores de la Fase 3.
 * <ul>
 *   <li>Síncrono: {@code POST {base}/api/v1/documents}. Alfa recibe un sobre JSON con el XML dentro; Beta, el
 *       texto por segmentos con la cabecera {@code X-Document-Id}. Responde {@link Decision}.</li>
 *   <li>Lotes: Alfa recibe un ZIP en {@code POST {base}/api/v1/batches}; Beta recibe el archivo de segmentos y
 *       su manifiesto en su carpeta SFTP simulada. Ambos dejan {@link BatchResult} en la carpeta de retorno.</li>
 *   <li>Respaldo (ADR-06): la constancia oficial de cada comprobante autorizado queda en {@code receipts/}.</li>
 * </ul>
 */
public final class ProviderProtocol {
    private ProviderProtocol() {}

    public static final String DOCUMENTS_PATH = "/api/v1/documents";
    public static final String BATCHES_PATH = "/api/v1/batches";
    public static final String DOCUMENT_ID_HEADER = "X-Document-Id";
    /** Cabecera de inyección de fallos de los simuladores (solo pruebas y escenarios). */
    public static final String FAULT_HEADER = "X-Sim-Fault";

    /** Sobre JSON del proveedor Alfa (y Gamma) para el envío síncrono. */
    public record DocumentEnvelope(String documentId, String format, String content, String sha256) {}

    /** Decisión del proveedor sobre un comprobante. */
    public record Decision(String documentId, String status, String code, String message, String authorizationNumber,
                           String receiptSha256) {
        public boolean authorized() { return "AUTHORIZED".equals(status); }
    }

    /** Entrada del manifiesto de un lote: posición en el lote y clave del comprobante. */
    public record ManifestEntry(int index, String entryName, String documentId) {}

    public record BatchManifest(String batchId, String provider, String country, List<ManifestEntry> documents) {}

    /** Resultado de un lote, que el proveedor deja en la carpeta de retorno. */
    public record BatchResult(String batchId, String provider, List<Decision> results) {}

    /** Constancia oficial de la autoridad (respaldo). */
    public record Receipt(String documentId, String provider, String status, String authorizationNumber, String issuedAt,
                          String documentSha256) {}
}
