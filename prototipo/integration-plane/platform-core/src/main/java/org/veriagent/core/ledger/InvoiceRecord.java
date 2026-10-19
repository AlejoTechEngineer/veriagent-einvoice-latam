package org.veriagent.core.ledger;

import org.veriagent.core.state.InvoiceState;

import java.time.Instant;

/** Fila de {@code authorization_log}: estado y auditoría de un comprobante. */
public record InvoiceRecord(
    long id,
    String invoiceKey,
    String correlationId,
    String invoiceNumber,
    String documentType,
    String country,
    String channel,
    String provider,
    Integer mappingVersion,
    InvoiceState status,
    String canonicalPayload,
    String transformedPayload,
    String errorCode,
    String errorMessage,
    String authorizationNumber,
    int retryCount,
    String lastActor,
    Instant receivedAt,
    Instant sentAt,
    Instant authorizedAt,
    Instant updatedAt) {
}
