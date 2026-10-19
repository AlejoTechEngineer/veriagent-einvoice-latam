package org.veriagent.core.testsupport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.veriagent.core.audit.AuditService;
import org.veriagent.core.correlation.CorrelationContext;
import org.veriagent.core.security.Actor;
import org.veriagent.core.security.IdentityGuard;

import java.util.Map;

/**
 * Controlador que solo existe en las pruebas del núcleo: reproduce el patrón de una decisión (solo REVIEWER,
 * identidad del token, auditoría) para probar la seguridad compartida sin depender de un servicio.
 */
@RestController
@ConditionalOnProperty(name = "veriagent.test.secured-probe", havingValue = "true")
public class SecuredProbeController {
    private final AuditService audit;

    public SecuredProbeController(AuditService audit) {
        this.audit = audit;
    }

    public record DecisionBody(String decision, String reviewer, String comment) {}

    @GetMapping("/api/probe/ping")
    public Map<String, String> ping() {
        return Map.of("correlationId", CorrelationContext.current());
    }

    @PostMapping("/api/probe/decision")
    @PreAuthorize("hasRole('REVIEWER')")
    public Map<String, String> decide(@RequestBody DecisionBody body) {
        Actor actor = IdentityGuard.requireConsistent(body.reviewer());
        audit.record("PROBE_DECISION", "probe-1", body.decision());
        return Map.of("decidedBy", actor.subject(), "role", actor.role().name());
    }
}
