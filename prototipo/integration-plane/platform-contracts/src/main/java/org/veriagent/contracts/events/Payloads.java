package org.veriagent.contracts.events;

import org.veriagent.contracts.model.InvoiceRequest;

import java.util.List;

/** Cargas útiles tipadas de los eventos. Cada una corresponde a un esquema JSON versionado. */
public final class Payloads {
    private Payloads() {}

    /** Canal de emisión: síncrono (fachada REST) o asíncrono por lotes (archivo depositado). */
    public enum Channel { SYNC, BATCH }

    /** Resultado del proveedor para un comprobante. */
    public enum Outcome { AUTHORIZED, REJECTED }

    /** Estado final que se devuelve al sistema de origen. */
    public enum FinalStatus { AUTHORIZED, FAILED }

    /** Categoría de un error que termina en la DLQ. */
    public enum ErrorCategory { RECOVERABLE, NON_RECOVERABLE }

    /** {@code {pais}.invoice.request.v1}: un comprobante canónico recibido por la ingesta. */
    public record InvoiceRequested(Channel channel, String sourceFile, InvoiceRequest invoice) {}

    /** {@code {pais}.{proveedor}.request.v1}: comprobante enrutado a un proveedor. */
    public record ProviderRequested(String provider, String routingRule, InvoiceRequest invoice) {}

    /** {@code {pais}.{proveedor}.response.v1}: respuesta del proveedor (fuente primaria) o constancia oficial (respaldo). */
    public record ProviderResponded(String provider, String batchId, Outcome outcome, String providerCode,
                                    String message, String authorizationNumber, String receiptSha256,
                                    String source) {}

    /** Hallazgo del kit incluido en una respuesta o en la DLQ. */
    public record KitFinding(String ruleId, String type, String location, String message, String source,
                             String sourceSha256, String ruleVersion) {}

    /** {@code {pais}.invoice.response.v1}: estado final del comprobante para el sistema de origen. */
    public record InvoiceResponded(FinalStatus status, Channel channel, String provider, String reasonCode,
                                   String reason, String authorizationNumber, List<KitFinding> findings) {}

    /** {@code {pais}.invoice.dlq.v1}: comprobante que no pudo completarse. */
    public record InvoiceDeadLettered(String stage, ErrorCategory category, int attempts, String lastError,
                                      String provider, List<KitFinding> findings) {}

    /** {@code platform.mapping.activated.v1}: una versión de mapeo aprobada pasó a ser la vigente. */
    public record MappingActivated(String provider, String country, int version, String mappingSha256,
                                   String activatedBy, String reason) {}
}
