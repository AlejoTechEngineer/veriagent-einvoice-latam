package org.veriagent.invoiceregistry;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.veriagent.core.audit.AuditService;
import org.veriagent.core.bus.FlowEvents;
import org.veriagent.core.dlq.DlqRepository;
import org.veriagent.core.ledger.InvoiceLedger;
import org.veriagent.core.ledger.InvoiceRecord;
import org.veriagent.core.state.InvoiceState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * invoice-registry (tabla 11): consulta de estado y auditoría de cada comprobante. Solo lectura. Incluye el
 * balance de conservación: entradas = autorizados + rechazados + DLQ + en curso.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Registro", description = "Consulta de estado, línea de tiempo y auditoría")
@ConditionalOnProperty(prefix = "veriagent.services", name = "invoice-registry", havingValue = "true", matchIfMissing = true)
public class InvoiceRegistryController {
    private final InvoiceLedger ledger;
    private final AuditService audit;
    private final DlqRepository dlq;
    private final JdbcClient jdbc;

    public InvoiceRegistryController(InvoiceLedger ledger, AuditService audit, DlqRepository dlq, JdbcClient jdbc) {
        this.ledger = ledger;
        this.audit = audit;
        this.dlq = dlq;
        this.jdbc = jdbc;
    }

    @Operation(summary = "Comprobantes con filtros (estado, país, proveedor, canal, número)")
    @GetMapping("/invoices")
    public List<Map<String, Object>> search(@RequestParam(required = false) InvoiceState status,
                                            @RequestParam(required = false) String country,
                                            @RequestParam(required = false) String provider,
                                            @RequestParam(required = false) String channel,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(defaultValue = "50") int limit,
                                            @RequestParam(defaultValue = "0") int offset) {
        return ledger.search(status, country, provider, channel, q, limit, offset).stream().map(InvoiceRegistryController::summary).toList();
    }

    @Operation(summary = "Detalle: estado, documento transformado, hallazgos del kit con procedencia, línea de tiempo y auditoría")
    @GetMapping("/invoices/{key}")
    public Map<String, Object> detail(@PathVariable String key) {
        InvoiceRecord r = ledger.find(key).orElseThrow(() -> new NoSuchElementException("Comprobante no encontrado: " + key));
        Map<String, Object> m = summary(r);
        m.put("canonical", r.canonicalPayload());
        m.put("transformed", r.transformedPayload());
        m.put("findings", FlowEvents.findingsFrom(r.findings()));
        m.put("timeline", ledger.history(key));
        m.put("audit", audit.forTarget("comprobante:" + key));
        m.put("dlq", jdbc.sql("SELECT id, stage, category, status, last_error, triage, resolved_by, created_at FROM dlq_entry WHERE invoice_key = :k ORDER BY id")
            .param("k", key).query().listOfRows());
        m.put("archive", jdbc.sql("SELECT seq, object_path, object_sha256, entry_hash, retention_until FROM legal_archive_entry WHERE invoice_key = :k")
            .param("k", key).query().listOfRows());
        return m;
    }

    @Operation(summary = "Indicadores: conteo por estado, por país y proveedor, y balance de conservación")
    @GetMapping("/invoices/stats")
    public Map<String, Object> stats() {
        Map<InvoiceState, Long> by = ledger.countByStatus();
        long total = ledger.count();
        long inFlight = by.get(InvoiceState.RECEIVED) + by.get(InvoiceState.PENDING_BATCH) + by.get(InvoiceState.SENT);
        Map<String, Object> conservation = new LinkedHashMap<>();
        conservation.put("registered", total);
        conservation.put("authorized", by.get(InvoiceState.AUTHORIZED));
        conservation.put("failed", by.get(InvoiceState.FAILED));
        conservation.put("dlq", by.get(InvoiceState.DLQ));
        conservation.put("inFlight", inFlight);
        conservation.put("balanced", total == by.get(InvoiceState.AUTHORIZED) + by.get(InvoiceState.FAILED) + by.get(InvoiceState.DLQ) + inFlight);
        conservation.put("invalidInputsInDlq", dlq.countOpenWithoutInvoice());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("byStatus", by);
        m.put("byCountryProvider", jdbc.sql("SELECT country, provider, status, COUNT(*) AS n FROM authorization_log GROUP BY country, provider, status ORDER BY country, provider, status")
            .query().listOfRows());
        m.put("conservation", conservation);
        m.put("dlqOpen", dlq.count(DlqRepository.Status.OPEN));
        return m;
    }

    @Operation(summary = "Últimas entradas de auditoría")
    @GetMapping("/audit")
    public List<AuditService.Entry> audit(@RequestParam(defaultValue = "100") int limit) {
        return audit.recent(limit);
    }

    static Map<String, Object> summary(InvoiceRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("invoiceKey", r.invoiceKey());
        m.put("correlationId", r.correlationId());
        m.put("number", r.invoiceNumber());
        m.put("documentType", r.documentType());
        m.put("country", r.country());
        m.put("channel", r.channel());
        m.put("provider", r.provider());
        m.put("mappingVersion", r.mappingVersion());
        m.put("status", r.status());
        m.put("errorCode", r.errorCode());
        m.put("errorMessage", r.errorMessage());
        m.put("authorizationNumber", r.authorizationNumber());
        m.put("retryCount", r.retryCount());
        m.put("sourceFile", r.sourceFile());
        m.put("receivedAt", r.receivedAt());
        m.put("updatedAt", r.updatedAt());
        return m;
    }
}
