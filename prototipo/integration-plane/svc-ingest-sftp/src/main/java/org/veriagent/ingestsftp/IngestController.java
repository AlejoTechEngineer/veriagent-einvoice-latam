package org.veriagent.ingestsftp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.veriagent.core.VeriagentProperties;
import org.veriagent.core.io.SafeFiles;
import org.veriagent.core.web.ApiErrors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/** Carga de lotes (canal asíncrono): el archivo se deposita en el buzón o se procesa en el acto. */
@RestController
@RequestMapping("/api/ingest")
@Tag(name = "Ingesta", description = "Carga de lotes por el canal asíncrono")
@ConditionalOnProperty(prefix = "veriagent.services", name = "ingest-sftp", havingValue = "true", matchIfMissing = true)
public class IngestController {
    private final IngestService service;
    private final VeriagentProperties core;
    private final IngestProperties props;
    private final JdbcClient jdbc;

    public IngestController(IngestService service, VeriagentProperties core, IngestProperties props, JdbcClient jdbc) {
        this.service = service;
        this.core = core;
        this.props = props;
        this.jdbc = jdbc;
    }

    @Operation(summary = "Carga un lote (.json, .jsonl o .zip). mode=queue lo deja en el buzón; mode=now lo procesa ya")
    @PostMapping("/batches")
    @PreAuthorize("hasAnyRole('OPERATOR','SYSTEM')")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam(defaultValue = "now") String mode) throws IOException {
        if (file.getSize() > core.getLimits().getMaxBatchBytes()) {
            return ApiErrors.body(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "El archivo supera el tamaño máximo", null);
        }
        String name = SafeFiles.sanitize(file.getOriginalFilename());
        if ("queue".equals(mode)) {
            Path inbox = IngestConfiguration.inbox(core, props);
            Files.createDirectories(inbox);
            Path target = SafeFiles.resolveInside(inbox, name);
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            file.transferTo(tmp);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return ResponseEntity.accepted().body(Map.of("file", name, "status", "EN_BUZON"));
        }
        try {
            return ResponseEntity.ok(service.ingest(name, file.getBytes()));
        } catch (IngestService.RejectedFileException e) {
            return ApiErrors.body(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_REJECTED", e.getMessage(), null);
        }
    }

    @Operation(summary = "Últimos archivos recibidos con su resumen")
    @GetMapping("/files")
    public List<Map<String, Object>> files(@RequestParam(defaultValue = "50") int limit) {
        return jdbc.sql("SELECT * FROM ingest_file ORDER BY id DESC LIMIT :n").param("n", Math.max(1, Math.min(limit, 200)))
            .query().listOfRows();
    }
}
