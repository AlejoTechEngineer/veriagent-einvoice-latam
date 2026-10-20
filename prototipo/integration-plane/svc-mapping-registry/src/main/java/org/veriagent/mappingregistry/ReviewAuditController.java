package org.veriagent.mappingregistry;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.veriagent.core.security.IdentityGuard;
import org.veriagent.core.web.ApiErrors;

import java.util.List;
import java.util.Map;

/**
 * Auditoría por muestreo de decisiones de revisión aprobadas (A-10). Muestrear: OPERATOR o SYSTEM (tarea
 * programada). Consultar la cola: REVIEWER, OPERATOR o SYSTEM. Segunda revisión: solo REVIEWER, con la identidad del
 * token y distinta de quien aprobó. Los agentes no muestrean ni revisan (403).
 */
@RestController
@RequestMapping("/api/review-audits")
@Tag(name = "Auditoría por muestreo", description = "Segunda revisión de decisiones aprobadas elegidas al azar")
@ConditionalOnProperty(name = "veriagent.mappings.source", havingValue = "registry")
public class ReviewAuditController {
    private final ReviewAudit audits;

    public ReviewAuditController(ReviewAudit audits) {
        this.audits = audits;
    }

    /** Parámetros opcionales del muestreo; sin ellos se usan la tasa y la semilla configuradas. */
    public record SampleRequest(Double rate, Long seed) {}

    /** Resultado de la segunda revisión; {@code reviewer} es opcional y, si viene, debe coincidir con el token. */
    public record Outcome(ReviewAudit.Status outcome, String note, String reviewer) {}

    @Operation(summary = "Sortea decisiones aprobadas y las pone en la cola de segunda revisión (OPERATOR o SYSTEM)")
    @PostMapping("/sample")
    @PreAuthorize("hasAnyRole('OPERATOR','SYSTEM')")
    public ResponseEntity<ReviewAudit.Sample> sample(@RequestBody(required = false) SampleRequest body) {
        var s = audits.sample(body == null ? null : body.rate(), body == null ? null : body.seed());
        return ResponseEntity.status(HttpStatus.CREATED).body(s);
    }

    @Operation(summary = "Cola de auditoría (por estado: PENDING, CONFIRMED o DISPUTED)")
    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER','OPERATOR','SYSTEM')")
    public List<ReviewAudit.Item> list(@RequestParam(required = false) ReviewAudit.Status status,
                                       @RequestParam(defaultValue = "100") int limit) {
        return audits.list(status, limit);
    }

    @Operation(summary = "Segunda revisión de una decisión muestreada (solo REVIEWER, distinto de quien aprobó)")
    @PostMapping("/{id}/decision")
    @PreAuthorize("hasRole('REVIEWER')")
    public ReviewAudit.Item decide(@PathVariable long id, @RequestBody Outcome body) {
        if (body == null) throw new IllegalArgumentException("Falta el resultado de la segunda revisión");
        IdentityGuard.requireConsistent(body.reviewer());
        return audits.decide(id, body.outcome(), body.note());
    }

    @ExceptionHandler(MappingRegistry.ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(MappingRegistry.ConflictException e) {
        return ApiErrors.body(HttpStatus.CONFLICT, "REVIEW_AUDIT_STATE", e.getMessage(), null);
    }
}
