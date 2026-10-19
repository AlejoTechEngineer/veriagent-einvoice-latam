package org.veriagent.contracts;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.veriagent.contracts.events.EventTypes;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Valida documentos contra los esquemas JSON versionados del módulo (JSON Schema 2020-12). El canónico se
 * valida también dentro de las cargas que lo transportan, de modo que un evento no puede llevar un
 * comprobante que no cumpla el contrato.
 */
public final class ContractValidator {
    public static final String CANONICAL = "canonical/invoice-request.v1.json";
    public static final String ENVELOPE = "events/envelope.v1.json";

    /** Cargas útiles que contienen un comprobante canónico en el campo {@code invoice}. */
    private static final Map<String, String> EMBEDDED_INVOICE = Map.of(
        EventTypes.INVOICE_REQUESTED, "/invoice",
        EventTypes.PROVIDER_REQUESTED, "/invoice");

    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private final Map<String, Schema> cache = new ConcurrentHashMap<>();

    /** Errores de validación del documento contra el esquema indicado; lista vacía si es válido. */
    public List<String> validate(String schemaName, JsonNode document) {
        List<String> out = new ArrayList<>();
        for (Error e : schema(schemaName).validate(document)) {
            out.add(e.getInstanceLocation() + ": " + e.getMessage());
        }
        return out;
    }

    /** Valida el sobre, la carga útil según su tipo y, si la carga trae un comprobante, el canónico. */
    public List<String> validateEvent(JsonNode envelope) {
        List<String> out = new ArrayList<>(validate(ENVELOPE, envelope));
        if (!out.isEmpty()) return out;
        String type = envelope.get("eventType").asString();
        String payloadSchema = EventTypes.PAYLOAD_SCHEMAS.get(type);
        if (payloadSchema == null) {
            out.add("eventType: tipo de evento desconocido: " + type);
            return out;
        }
        JsonNode payload = envelope.get("payload");
        validate(payloadSchema, payload).forEach(m -> out.add("payload " + m));
        String pointer = EMBEDDED_INVOICE.get(type);
        if (pointer != null && out.isEmpty()) {
            validate(CANONICAL, payload.at(pointer)).forEach(m -> out.add("payload" + pointer + " " + m));
        }
        return out;
    }

    private Schema schema(String name) {
        return cache.computeIfAbsent(name, n -> {
            try (InputStream in = ContractValidator.class.getResourceAsStream("/contracts/" + n)) {
                if (in == null) throw new IllegalArgumentException("Esquema no encontrado: " + n);
                return registry.getSchema(ContractJson.mapper().readTree(in));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
