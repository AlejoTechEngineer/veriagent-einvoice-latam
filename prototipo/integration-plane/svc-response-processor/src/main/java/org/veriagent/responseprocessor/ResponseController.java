package org.veriagent.responseprocessor;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.veriagent.core.audit.AuditService;

import java.util.Map;

/** Estadísticas del procesamiento de respuestas y conciliación bajo demanda. */
@RestController
@RequestMapping("/api/responses")
@Tag(name = "Respuestas", description = "Fuente primaria y respaldo (ADR-06)")
@ConditionalOnProperty(prefix = "veriagent.services", name = "response-processor", havingValue = "true", matchIfMissing = true)
public class ResponseController {
    private final ResponseProcessor processor;
    private final AuditService audit;

    public ResponseController(ResponseProcessor processor, AuditService audit) {
        this.processor = processor;
        this.audit = audit;
    }

    @Operation(summary = "Contadores de respuestas procesadas")
    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return processor.stats();
    }

    @Operation(summary = "Ejecuta la conciliación con las constancias oficiales")
    @PostMapping("/reconcile")
    @PreAuthorize("hasAnyRole('OPERATOR','SYSTEM')")
    public ResponseProcessor.ReconcileResult reconcile() {
        var r = processor.reconcile();
        audit.record("RECONCILE", "respuestas", "desde constancias=" + r.fromReceipts() + " sin respuesta=" + r.timedOut());
        return r;
    }
}
