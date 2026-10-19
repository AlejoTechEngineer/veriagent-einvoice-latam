package org.veriagent.contracts;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Configuración JSON compartida por productores y consumidores de eventos: fechas ISO-8601, decimales como
 * BigDecimal y tolerancia a campos nuevos (compatibilidad BACKWARD de los esquemas).
 */
public final class ContractJson {
    private static final JsonMapper MAPPER = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .build();

    private ContractJson() {}

    public static JsonMapper mapper() { return MAPPER; }
}
