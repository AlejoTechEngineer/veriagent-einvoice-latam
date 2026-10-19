package org.veriagent.simulators;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.veriagent.contracts.ContractJson;
import org.veriagent.core.VeriagentProperties;
import org.veriagent.core.audit.AuditService;
import org.veriagent.core.security.CurrentActor;
import org.veriagent.core.web.ApiErrors;
import org.veriagent.provider.client.ProviderClient;
import org.veriagent.provider.client.ProviderProtocol;
import org.veriagent.provider.client.ProviderProtocol.BatchManifest;
import org.veriagent.provider.client.ProviderProtocol.BatchResult;
import org.veriagent.provider.client.ProviderProtocol.Decision;
import org.veriagent.provider.client.ProviderProtocol.DocumentEnvelope;
import org.veriagent.simulators.FaultInjector.Fault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * API de los proveedores simulados ({@code /sim/{proveedor}/api/v1/**}, ver {@link ProviderProtocol}) y API de
 * administración de fallos ({@code /api/simulators}, rol OPERATOR para cambiar). Las rutas {@code /sim/} son
 * públicas solo en el perfil local porque representan sistemas externos.
 */
@RestController
@Tag(name = "Simuladores de proveedores", description = "Alfa, Beta y Gamma deterministas, con fallos inyectables")
@ConditionalOnProperty(prefix = "veriagent.services", name = "provider-simulators", havingValue = "true")
public class SimulatorController {
    private final ProviderSimulator sim;
    private final AuditService audit;
    private final VeriagentProperties core;
    private final Duration slowDelay;
    private final ExecutorService batches = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "simulador-lotes");
        t.setDaemon(true);
        return t;
    });

    public SimulatorController(ProviderSimulator sim, AuditService audit, VeriagentProperties core, SimulatorProperties props) {
        this.sim = sim;
        this.audit = audit;
        this.core = core;
        this.slowDelay = props.getSlowDelay();
    }

    @Operation(summary = "Envío síncrono de un documento al proveedor simulado")
    @PostMapping(path = "/sim/{provider}" + ProviderProtocol.DOCUMENTS_PATH)
    public ResponseEntity<?> document(@PathVariable String provider, @RequestBody String body,
                                      @RequestHeader(value = ProviderProtocol.DOCUMENT_ID_HEADER, required = false) String documentId,
                                      @RequestHeader(value = "Content-Type", required = false) String contentType,
                                      @RequestHeader(value = ProviderProtocol.FAULT_HEADER, required = false) String faultHeader)
        throws InterruptedException {
        String p = ProviderSimulator.requireProvider(provider);
        if (body.length() > 2 * 1024 * 1024) return error(HttpStatus.PAYLOAD_TOO_LARGE, "Documento demasiado grande");
        String id;
        String content;
        if (contentType != null && contentType.startsWith(MediaType.APPLICATION_JSON_VALUE)) {
            DocumentEnvelope env = ContractJson.mapper().readValue(body, DocumentEnvelope.class);
            if (env.documentId() == null || env.content() == null) return error(HttpStatus.BAD_REQUEST, "Sobre incompleto");
            if (env.sha256() != null && !env.sha256().equals(ProviderClient.sha256(env.content()))) {
                return error(HttpStatus.BAD_REQUEST, "La huella SHA-256 del sobre no coincide con el contenido");
            }
            id = env.documentId();
            content = env.content();
        } else {
            if (documentId == null || documentId.isBlank()) return error(HttpStatus.BAD_REQUEST, "Falta la cabecera " + ProviderProtocol.DOCUMENT_ID_HEADER);
            id = documentId;
            content = body;
        }
        Optional<Fault> fault = sim.faults().next(p, faultHeader);
        if (fault.isPresent()) {
            switch (fault.get()) {
                case UNAVAILABLE -> {
                    sim.count(p, "fault-unavailable");
                    return error(HttpStatus.SERVICE_UNAVAILABLE, "Proveedor no disponible (fallo simulado)");
                }
                case SLOW -> {
                    sim.count(p, "fault-slow");
                    Thread.sleep(slowDelay.toMillis());
                }
                default -> { }
            }
        }
        Fault f = fault.orElse(null);
        Decision d = sim.decide(p, id, content, f == Fault.REJECT_CATALOG);
        if (f == Fault.LOST_RESPONSE) {
            sim.count(p, "fault-lost-response");
            return error(HttpStatus.GATEWAY_TIMEOUT, "La respuesta del proveedor se perdió (fallo simulado)");
        }
        if (f == Fault.DUPLICATE) {
            sim.count(p, "fault-duplicate");
            sim.writeResult(p, "sync-dup-" + id, new BatchResult("sync-dup-" + id, p, List.of(d)));
        }
        return ResponseEntity.ok(d);
    }

    @Operation(summary = "Recepción de un lote ZIP con manifest.json (Alfa y Gamma); el resultado va a la carpeta de retorno")
    @PostMapping(path = "/sim/{provider}" + ProviderProtocol.BATCHES_PATH, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> batch(@PathVariable String provider, @RequestParam("file") MultipartFile file,
                                   @RequestHeader(value = ProviderProtocol.FAULT_HEADER, required = false) String faultHeader)
        throws IOException {
        String p = ProviderSimulator.requireProvider(provider);
        if (file.getSize() > core.getLimits().getMaxBatchBytes()) return error(HttpStatus.PAYLOAD_TOO_LARGE, "Lote demasiado grande");
        Map<String, String> entries = unzip(file.getBytes(), core.getLimits().getMaxUncompressedBytes());
        String manifestJson = entries.remove("manifest.json");
        if (manifestJson == null) return error(HttpStatus.BAD_REQUEST, "El lote no trae manifest.json");
        BatchManifest manifest = ContractJson.mapper().readValue(manifestJson, BatchManifest.class);
        if (manifest.documents().size() > core.getLimits().getMaxInvoicesPerBatch()) {
            return error(HttpStatus.PAYLOAD_TOO_LARGE, "Demasiados documentos en el lote");
        }
        Optional<Fault> fault = sim.faults().next(p, faultHeader);
        if (fault.orElse(null) == Fault.UNAVAILABLE) {
            sim.count(p, "fault-unavailable");
            return error(HttpStatus.SERVICE_UNAVAILABLE, "Proveedor no disponible (fallo simulado)");
        }
        batches.submit(() -> {
            try {
                if (fault.orElse(null) == Fault.SLOW) Thread.sleep(slowDelay.toMillis());
                sim.processBatch(p, manifest, entries, fault.orElse(null));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        return ResponseEntity.accepted().body(Map.of("batchId", manifest.batchId(), "documents", manifest.documents().size(),
            "estado", "Lote recibido; el resultado se dejará en la carpeta de retorno"));
    }

    @Operation(summary = "Fallos activos y contadores de los simuladores")
    @GetMapping("/api/simulators")
    public Map<String, Object> state() {
        return Map.of("providers", ProviderSimulator.PROVIDERS.stream().sorted().toList(), "faults", sim.faults().snapshot(),
            "stats", sim.stats());
    }

    public record FaultRequest(String fault, Integer remaining) {}

    @Operation(summary = "Activa un fallo en un proveedor simulado (solo OPERATOR)")
    @PostMapping("/api/simulators/{provider}/faults")
    @PreAuthorize("hasRole('OPERATOR')")
    public Map<String, Object> setFault(@PathVariable String provider, @RequestBody FaultRequest body) {
        String p = ProviderSimulator.requireProvider(provider);
        if (body == null || body.fault() == null) throw new IllegalArgumentException("Falta el fallo");
        Fault f = Fault.parse(body.fault());
        int remaining = body.remaining() == null ? -1 : body.remaining();
        String actor = CurrentActor.get().subject();
        sim.faults().set(p, f, remaining, actor);
        audit.record("SIMULATOR_FAULT_SET", "simulador:" + p, f + " restantes=" + remaining);
        return Map.of("provider", p, "fault", f, "remaining", remaining, "setBy", actor);
    }

    @Operation(summary = "Retira el fallo de un proveedor simulado (solo OPERATOR)")
    @DeleteMapping("/api/simulators/{provider}/faults")
    @PreAuthorize("hasRole('OPERATOR')")
    public Map<String, Object> clearFault(@PathVariable String provider) {
        String p = ProviderSimulator.requireProvider(provider);
        sim.faults().clear(p);
        audit.record("SIMULATOR_FAULT_CLEARED", "simulador:" + p, null);
        return Map.of("provider", p, "fault", "ninguno");
    }

    /** Descomprime con límites: tamaño total, nombres saneados y sin rutas (protección contra bombas y traversal). */
    static Map<String, String> unzip(byte[] zip, long maxUncompressed) throws IOException {
        Map<String, String> out = new HashMap<>();
        long total = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name.contains("/") || name.contains("\\") || name.contains("..")) {
                    throw new IllegalArgumentException("Nombre de entrada no permitido en el lote: " + name);
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zis.read(buf)) > 0) {
                    total += n;
                    if (total > maxUncompressed) throw new IllegalArgumentException("El lote descomprimido supera el límite");
                    bos.write(buf, 0, n);
                }
                out.put(name, bos.toString(StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ApiErrors.body(status, "SIMULATOR", message, null);
    }
}
