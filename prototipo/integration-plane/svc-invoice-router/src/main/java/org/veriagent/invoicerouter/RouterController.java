package org.veriagent.invoicerouter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.provider.routing.RoutingTable;

import java.util.LinkedHashMap;
import java.util.Map;

/** Consulta de la tabla de enrutamiento vigente y vista previa de la ruta de un comprobante (solo lectura). */
@RestController
@RequestMapping("/api/router")
@Tag(name = "Enrutador", description = "Tabla de enrutamiento versionada")
@ConditionalOnProperty(prefix = "veriagent.services", name = "invoice-router", havingValue = "true", matchIfMissing = true)
public class RouterController {
    private final RoutingTable table;
    private final InvoiceRouter router;

    public RouterController(RoutingTable table, InvoiceRouter router) {
        this.table = table;
        this.router = router;
    }

    @Operation(summary = "Tabla de enrutamiento vigente con su versión y huella")
    @GetMapping("/table")
    public Map<String, Object> table() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", table.version());
        m.put("sha256", table.sha256());
        m.put("rules", table.rules());
        m.put("routed", router.routedCount());
        m.put("unrouted", router.unroutedCount());
        return m;
    }

    @Operation(summary = "Ruta que tomaría un comprobante canónico (no lo envía)")
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody InvoiceRequest invoice) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("invoiceKey", invoice.invoiceKey());
        table.route(invoice).ifPresentOrElse(r -> { m.put("provider", r.provider()); m.put("rule", r.ruleId()); },
            () -> m.put("provider", null));
        return m;
    }
}
