package org.veriagent.core.bus;

import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.events.EventTypes;
import org.veriagent.contracts.events.Payloads;
import org.veriagent.contracts.events.Payloads.Channel;
import org.veriagent.contracts.events.Payloads.ErrorCategory;
import org.veriagent.contracts.events.Payloads.FinalStatus;
import org.veriagent.contracts.events.Payloads.KitFinding;
import org.veriagent.contracts.events.Topics;
import org.veriagent.contracts.model.Country;
import tools.jackson.core.type.TypeReference;

import java.util.List;

/**
 * Publicación de los eventos de cierre de un comprobante con sus contratos: la respuesta final
 * ({@code {pais}.invoice.response.v1}) y la cuarentena ({@code {pais}.invoice.dlq.v1}).
 */
public class FlowEvents {
    private final EventBus bus;

    public FlowEvents(EventBus bus) {
        this.bus = bus;
    }

    public void response(String invoiceKey, String country, String producer, FinalStatus status, Channel channel,
                         String provider, String reasonCode, String reason, String authorizationNumber, List<KitFinding> findings) {
        var payload = new Payloads.InvoiceResponded(status, channel, provider, reasonCode, reason, authorizationNumber,
            findings == null || findings.isEmpty() ? null : findings);
        bus.publish(Topics.invoiceResponse(Country.valueOf(country)),
            Events.of(EventTypes.INVOICE_RESPONDED, country, invoiceKey, producer, payload));
    }

    public void deadLetter(String invoiceKey, String country, String producer, String stage, ErrorCategory category,
                           int attempts, String lastError, String provider, List<KitFinding> findings) {
        var payload = new Payloads.InvoiceDeadLettered(stage, category, attempts,
            lastError == null || lastError.isBlank() ? "sin detalle" : (lastError.length() > 2000 ? lastError.substring(0, 2000) : lastError),
            provider, findings);
        bus.publish(Topics.invoiceDlq(Country.valueOf(country)),
            Events.of(EventTypes.INVOICE_DEAD_LETTERED, country, invoiceKey, producer, payload));
    }

    /** Serialización de hallazgos para guardarlos en la base de datos. */
    public static String findingsJson(List<KitFinding> findings) {
        return findings == null || findings.isEmpty() ? null : ContractJson.mapper().writeValueAsString(findings);
    }

    public static List<KitFinding> findingsFrom(String json) {
        if (json == null || json.isBlank()) return List.of();
        return ContractJson.mapper().readValue(json, new TypeReference<List<KitFinding>>() {});
    }
}
