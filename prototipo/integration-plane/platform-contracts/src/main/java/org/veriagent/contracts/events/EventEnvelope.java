package org.veriagent.contracts.events;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Sobre común de todos los eventos (esquema {@code contracts/events/envelope.v1.json}). El identificador de
 * correlación viaja de punta a punta; la clave del comprobante permite la idempotencia y el particionado.
 */
public record EventEnvelope(
    String eventId,
    String eventType,
    int schemaVersion,
    String correlationId,
    String invoiceKey,
    String country,
    Instant occurredAt,
    String producer,
    JsonNode payload) {
}
