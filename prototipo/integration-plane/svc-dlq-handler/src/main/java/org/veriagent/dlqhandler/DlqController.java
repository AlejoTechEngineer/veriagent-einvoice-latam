package org.veriagent.dlqhandler;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.veriagent.core.audit.AuditService;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.dlq.DlqRepository;
import org.veriagent.core.ledger.InvoiceLedger;
import org.veriagent.contracts.ContractJson;
import org.veriagent.core.security.CurrentActor;
import org.veriagent.core.security.IdentityGuard;
import org.veriagent.core.web.ApiErrors;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * API de la cola DLQ. Reprocesar y descartar son decisiones: solo OPERATOR, con la identidad del token (los
 * agentes reciben 403). Consultar está permitido a cualquier rol autenticado.
 */
@RestController
@RequestMapping("/api/dlq")
@Tag(name = "Cola DLQ", description = "Cuarentena, reproceso y descarte")
@ConditionalOnProperty(prefix = "veriagent.services", name = "dlq-handler", havingValue = "true", matchIfMissing = true)
public class DlqController {
    private final DlqHandler handler;
    private final DlqRepository dlq;
    private final AuditService audit;

    public DlqController(DlqHandler handler, DlqRepository dlq, AuditService audit) {
        this.handler = handler;
        this.dlq = dlq;
        this.audit = audit;
    }

    /** Clasificación del agente de triaje: solo una recomendación; no cambia el estado de la entrada. */
    public record Triage(String classification, String defectType, String recommendation, String rationale,
                         List<String> evidence) {}

    /** Cuerpo de una decisión. {@code operator} es opcional y, si viene, debe coincidir con el token. */
    public record Decision(String comment, String operator) {}

    @Operation(summary = "Entradas de la DLQ (OPEN, REPROCESSED, DISCARDED)")
    @GetMapping
    public List<DlqRepository.Entry> list(@RequestParam(required = false) DlqRepository.Status status,
                                          @RequestParam(defaultValue = "100") int limit) {
        return dlq.list(status, Math.max(1, Math.min(limit, 500)));
    }

    @Operation(summary = "Detalle de una entrada")
    @GetMapping("/{id}")
    public DlqRepository.Entry get(@PathVariable long id) {
        return dlq.find(id).orElseThrow(() -> new NoSuchElementException("Entrada de DLQ no encontrada: " + id));
    }

    @Operation(summary = "Reprocesa el comprobante con el mapeo vigente (solo OPERATOR)")
    @PostMapping("/{id}/reprocess")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<?> reprocess(@PathVariable long id, @RequestBody(required = false) Decision body) {
        IdentityGuard.requireConsistent(body == null ? null : body.operator());
        try {
            return ResponseEntity.ok(handler.reprocess(id, body == null ? null : body.comment()));
        } catch (IllegalStateException e) {
            return ApiErrors.body(HttpStatus.CONFLICT, "NOT_REPROCESSABLE", e.getMessage(), null);
        }
    }

    @Operation(summary = "Descarta la entrada (solo OPERATOR); el comprobante queda en DLQ")
    @PostMapping("/{id}/discard")
    @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<?> discard(@PathVariable long id, @RequestBody(required = false) Decision body) {
        IdentityGuard.requireConsistent(body == null ? null : body.operator());
        try {
            handler.discard(id, body == null ? null : body.comment());
            return ResponseEntity.ok(Map.of("id", id, "status", "DISCARDED"));
        } catch (IllegalStateException e) {
            return ApiErrors.body(HttpStatus.CONFLICT, "NOT_OPEN", e.getMessage(), null);
        }
    }

    @Operation(summary = "Registra el triaje de una entrada (AGENT o SYSTEM); es una recomendación y no reprocesa")
    @PostMapping("/{id}/triage")
    @PreAuthorize("hasAnyRole('AGENT','SYSTEM')")
    public Map<String, Object> triage(@PathVariable long id, @RequestBody Triage body) {
        var entry = dlq.find(id).orElseThrow(() -> new NoSuchElementException("Entrada de DLQ no encontrada: " + id));
        if (body == null || body.classification() == null || body.recommendation() == null) {
            throw new IllegalArgumentException("El triaje necesita clasificación y recomendación");
        }
        String by = CurrentActor.get().subject();
        Map<String, Object> t = new java.util.LinkedHashMap<>();
        t.put("classification", body.classification());
        t.put("defectType", body.defectType());
        t.put("recommendation", body.recommendation());
        t.put("rationale", body.rationale());
        t.put("evidence", body.evidence() == null ? List.of() : body.evidence());
        t.put("triagedBy", by);
        t.put("triagedAt", java.time.Instant.now().toString());
        dlq.saveTriage(id, ContractJson.mapper().writeValueAsString(t));
        audit.record("DLQ_TRIAGED", "dlq:" + id, body.classification() + " -> " + body.recommendation());
        return Map.of("id", id, "status", entry.status(), "triage", t);
    }

    @Configuration
    @EnableConfigurationProperties(DlqProperties.class)
    @ConditionalOnProperty(prefix = "veriagent.services", name = "dlq-handler", havingValue = "true", matchIfMissing = true)
    public static class DlqConfiguration {
        @Bean
        public DlqHandler dlqHandler(EventBus bus, InvoiceLedger ledger, DlqRepository dlq, AuditService audit, DlqProperties props) {
            return new DlqHandler(bus, ledger, dlq, audit, props);
        }
    }
}
