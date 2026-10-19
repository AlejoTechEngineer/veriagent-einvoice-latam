package org.veriagent.providerconnector;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.veriagent.contracts.model.InvoiceRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Invocación del conector entre servicios (perfil full, cuando la fachada y el conector corren en contenedores
 * distintos). Solo el rol SYSTEM: no es una API para personas ni para agentes.
 */
@RestController
@RequestMapping("/api/connectors")
@Tag(name = "Conectores", description = "Conectores síncronos por proveedor (uso interno)")
@ConditionalOnProperty(prefix = "veriagent.services", name = "provider-connector", havingValue = "true", matchIfMissing = true)
public class ConnectorController {
    private final ProviderConnector connector;

    public ConnectorController(ProviderConnector connector) {
        this.connector = connector;
    }

    @Operation(summary = "Transforma, verifica y envía un comprobante al proveedor (no cambia el registro)")
    @PostMapping("/{provider}/documents")
    @PreAuthorize("hasRole('SYSTEM')")
    public Map<String, Object> send(@PathVariable String provider, @RequestBody InvoiceRequest invoice) {
        var r = connector.send(invoice, provider.toLowerCase(java.util.Locale.ROOT), Map.of());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcome", r.outcome());
        m.put("attempts", r.attempts());
        if (r.decision() != null) m.put("decision", r.decision());
        if (r.prepared() != null) {
            m.put("mappingVersion", r.prepared().mappingVersion());
            m.put("verification", r.prepared().verification());
        }
        if (r.error() != null) m.put("error", r.error());
        return m;
    }
}
