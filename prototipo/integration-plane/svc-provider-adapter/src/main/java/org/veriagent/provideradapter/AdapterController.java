package org.veriagent.provideradapter;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.veriagent.core.audit.AuditService;

import java.util.List;
import java.util.Map;

/** Lotes enviados y envío forzado de lo pendiente. */
@RestController
@RequestMapping("/api/adapters")
@Tag(name = "Adaptadores", description = "Envío por lotes a los proveedores")
@ConditionalOnProperty(prefix = "veriagent.services", name = "provider-adapter", havingValue = "true", matchIfMissing = true)
public class AdapterController {
    private final ProviderAdapter adapter;
    private final JdbcClient jdbc;
    private final AuditService audit;

    public AdapterController(ProviderAdapter adapter, JdbcClient jdbc, AuditService audit) {
        this.adapter = adapter;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Operation(summary = "Últimos lotes enviados")
    @GetMapping("/batches")
    public List<Map<String, Object>> batches(@RequestParam(defaultValue = "50") int limit) {
        return jdbc.sql("SELECT * FROM provider_batch ORDER BY created_at DESC LIMIT :n")
            .param("n", Math.max(1, Math.min(limit, 200))).query().listOfRows();
    }

    @Operation(summary = "Envía ya todo lo agrupado pendiente")
    @PostMapping("/flush")
    @PreAuthorize("hasAnyRole('OPERATOR','SYSTEM')")
    public Map<String, Object> flush() {
        int n = adapter.flushAll();
        audit.record("ADAPTER_FLUSH", "adaptadores", "comprobantes enviados=" + n);
        return Map.of("sent", n, "blockedByKit", adapter.blockedCount());
    }
}
