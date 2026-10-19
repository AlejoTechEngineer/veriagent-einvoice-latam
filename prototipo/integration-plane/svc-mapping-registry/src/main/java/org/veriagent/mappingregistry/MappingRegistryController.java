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
import org.springframework.web.bind.annotation.RestController;
import org.veriagent.core.security.IdentityGuard;
import org.veriagent.core.web.ApiErrors;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * API del registro de mapeos. Proponer: AGENT, REVIEWER o SYSTEM (el kit decide si la propuesta puede revisarse).
 * Aprobar, rechazar, activar y revertir: solo REVIEWER, con la identidad del token. Los agentes nunca activan.
 */
@RestController
@RequestMapping("/api/mappings")
@Tag(name = "Registro de mapeos", description = "Versiones, control del kit, activación en caliente y reversión")
@ConditionalOnProperty(name = "veriagent.mappings.source", havingValue = "registry")
public class MappingRegistryController {
    private final MappingRegistry registry;

    public MappingRegistryController(MappingRegistry registry) {
        this.registry = registry;
    }

    public record Proposal(JsonNode mapping, String source, String proposalRef) {}

    /** Cuerpo de una decisión; {@code reviewer} es opcional y, si viene, debe coincidir con el token. */
    public record Decision(String note, String reviewer) {}

    @Operation(summary = "Proveedores y países con su versión vigente")
    @GetMapping
    public List<Map<String, Object>> overview() {
        return registry.overview();
    }

    @Operation(summary = "Versiones de un proveedor y país, con su verificación")
    @GetMapping("/{provider}/{country}/versions")
    public List<MappingRegistry.Version> versions(@PathVariable String provider, @PathVariable String country) {
        return registry.list(provider, country);
    }

    @Operation(summary = "Detalle de una versión")
    @GetMapping("/{provider}/{country}/versions/{version}")
    public MappingRegistry.Version version(@PathVariable String provider, @PathVariable String country, @PathVariable int version) {
        return registry.find(provider, country, version);
    }

    @Operation(summary = "Propone una versión candidata; el kit la verifica (bloqueada si no pasa)")
    @PostMapping("/{provider}/{country}/candidates")
    @PreAuthorize("hasAnyRole('AGENT','REVIEWER','SYSTEM')")
    public ResponseEntity<MappingRegistry.Version> propose(@PathVariable String provider, @PathVariable String country,
                                                           @RequestBody Proposal body) {
        if (body == null || body.mapping() == null || !body.mapping().isObject()) throw new IllegalArgumentException("Falta el mapeo");
        var v = registry.propose(provider, country, body.mapping(), body.source() == null ? "api" : body.source(), body.proposalRef());
        return ResponseEntity.status(HttpStatus.CREATED).body(v);
    }

    @Operation(summary = "Aprueba una versión candidata (solo REVIEWER; 409 si no pasó el kit)")
    @PostMapping("/{provider}/{country}/versions/{version}/approve")
    @PreAuthorize("hasRole('REVIEWER')")
    public MappingRegistry.Version approve(@PathVariable String provider, @PathVariable String country, @PathVariable int version,
                                           @RequestBody(required = false) Decision body) {
        IdentityGuard.requireConsistent(body == null ? null : body.reviewer());
        return registry.approve(provider, country, version, body == null ? null : body.note());
    }

    @Operation(summary = "Rechaza una versión (solo REVIEWER)")
    @PostMapping("/{provider}/{country}/versions/{version}/reject")
    @PreAuthorize("hasRole('REVIEWER')")
    public MappingRegistry.Version reject(@PathVariable String provider, @PathVariable String country, @PathVariable int version,
                                          @RequestBody(required = false) Decision body) {
        IdentityGuard.requireConsistent(body == null ? null : body.reviewer());
        return registry.reject(provider, country, version, body == null ? null : body.note());
    }

    @Operation(summary = "Activa una versión aprobada; adaptadores y conectores la usan en caliente (solo REVIEWER)")
    @PostMapping("/{provider}/{country}/versions/{version}/activate")
    @PreAuthorize("hasRole('REVIEWER')")
    public MappingRegistry.Version activate(@PathVariable String provider, @PathVariable String country, @PathVariable int version,
                                            @RequestBody(required = false) Decision body) {
        IdentityGuard.requireConsistent(body == null ? null : body.reviewer());
        return registry.activate(provider, country, version, body == null ? null : body.note());
    }

    @Operation(summary = "Revierte a la versión vigente anterior (solo REVIEWER)")
    @PostMapping("/{provider}/{country}/rollback")
    @PreAuthorize("hasRole('REVIEWER')")
    public MappingRegistry.Version rollback(@PathVariable String provider, @PathVariable String country,
                                            @RequestBody(required = false) Decision body) {
        IdentityGuard.requireConsistent(body == null ? null : body.reviewer());
        return registry.rollback(provider, country, body == null ? null : body.note());
    }

    @ExceptionHandler(MappingRegistry.ConflictException.class)
    public ResponseEntity<Map<String, Object>> conflict(MappingRegistry.ConflictException e) {
        return ApiErrors.body(HttpStatus.CONFLICT, "GATE_OR_STATE", e.getMessage(), null);
    }
}
