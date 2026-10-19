package org.veriagent.core.bus;

import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.ContractValidator;
import org.veriagent.contracts.events.EventEnvelope;
import org.veriagent.core.correlation.CorrelationContext;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Construcción, serialización y validación de sobres de evento. */
public final class Events {
    private static final ContractValidator VALIDATOR = new ContractValidator();

    private Events() {}

    /** Sobre con la correlación del hilo actual. */
    public static EventEnvelope of(String eventType, String country, String invoiceKey, String producer, Object payload) {
        return new EventEnvelope(UUID.randomUUID().toString(), eventType, 1, CorrelationContext.current(), invoiceKey,
            country, Instant.now(), producer, ContractJson.mapper().valueToTree(payload));
    }

    /** Carga útil tipada del evento. */
    public static <T> T payload(EventEnvelope e, Class<T> type) {
        return ContractJson.mapper().treeToValue(e.payload(), type);
    }

    public static String toJson(EventEnvelope e) {
        return ContractJson.mapper().writeValueAsString(e);
    }

    public static EventEnvelope fromJson(String json) {
        return ContractJson.mapper().readValue(json, EventEnvelope.class);
    }

    /** Lanza {@link ContractViolationException} si el evento no cumple su esquema versionado. */
    public static void requireValid(EventEnvelope e) {
        JsonNode node = ContractJson.mapper().valueToTree(e);
        List<String> errors = VALIDATOR.validateEvent(node);
        if (!errors.isEmpty()) {
            throw new ContractViolationException(e.eventType(), errors);
        }
    }

    /** Evento que no cumple el contrato: no se publica. */
    public static final class ContractViolationException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final List<String> errors;

        public ContractViolationException(String eventType, List<String> errors) {
            super("El evento " + eventType + " no cumple su contrato: " + errors);
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() { return errors; }
    }
}
