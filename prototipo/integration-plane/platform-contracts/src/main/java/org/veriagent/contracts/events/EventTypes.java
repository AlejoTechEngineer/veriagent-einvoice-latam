package org.veriagent.contracts.events;

import java.util.Map;

/** Tipos de evento y esquema JSON de su carga útil. */
public final class EventTypes {
    public static final String INVOICE_REQUESTED = "InvoiceRequested";
    public static final String PROVIDER_REQUESTED = "ProviderRequested";
    public static final String PROVIDER_RESPONDED = "ProviderResponded";
    public static final String INVOICE_RESPONDED = "InvoiceResponded";
    public static final String INVOICE_DEAD_LETTERED = "InvoiceDeadLettered";
    public static final String MAPPING_ACTIVATED = "MappingActivated";

    /** Tipo de evento → esquema de su carga útil (versión 1). */
    public static final Map<String, String> PAYLOAD_SCHEMAS = Map.of(
        INVOICE_REQUESTED, "events/invoice-requested.v1.json",
        PROVIDER_REQUESTED, "events/provider-requested.v1.json",
        PROVIDER_RESPONDED, "events/provider-responded.v1.json",
        INVOICE_RESPONDED, "events/invoice-responded.v1.json",
        INVOICE_DEAD_LETTERED, "events/invoice-dead-lettered.v1.json",
        MAPPING_ACTIVATED, "events/mapping-activated.v1.json");

    private EventTypes() {}
}
