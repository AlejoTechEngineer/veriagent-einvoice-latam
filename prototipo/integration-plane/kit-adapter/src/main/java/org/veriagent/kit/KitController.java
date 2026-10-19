package org.veriagent.kit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.tfg.kit.core.Mode;
import org.veriagent.core.web.ApiErrors;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * API del kit de verificación (apartado 4.4.4): {@code POST /api/kit/verify} devuelve 200 si el documento
 * pasa y 422 con los hallazgos si no. No cambia estado; exige token como todo el prototipo.
 */
@RestController
@RequestMapping("/api/kit")
@Tag(name = "Kit de verificación", description = "Verificación determinista con procedencia normativa")
public class KitController {
    static final int MAX_BODY = 2 * 1024 * 1024;
    private final KitVerifier verifier;

    public KitController(KitVerifier verifier) {
        this.verifier = verifier;
    }

    @Operation(summary = "Verifica un documento con un perfil (alfa, beta) y un modo (SCHEMA_ONLY, RUNTIME, CI)")
    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestParam(defaultValue = "alfa") String profile,
                                    @RequestParam(defaultValue = "RUNTIME") Mode mode,
                                    @RequestParam(defaultValue = "documento") String id,
                                    HttpServletRequest request) throws IOException {
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY + 1);
        }
        if (body.length > MAX_BODY) {
            return ApiErrors.body(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "El documento supera 2 MB", null);
        }
        if (body.length == 0) {
            return ApiErrors.body(HttpStatus.BAD_REQUEST, "EMPTY", "El documento está vacío", null);
        }
        KitResult r = verifier.verify(profile, id.length() > 80 ? id.substring(0, 80) : id,
            new String(body, StandardCharsets.UTF_8), mode, null);
        return ResponseEntity.status(r.passed() ? HttpStatus.OK : HttpStatus.UNPROCESSABLE_ENTITY).body(r);
    }

    @Operation(summary = "Perfiles disponibles con la huella digital de su especificación")
    @GetMapping("/profiles")
    public List<Map<String, String>> profiles() {
        return verifier.profiles().stream()
            .map(p -> Map.of("id", p.id(), "format", p.format(), "specDocument", p.specDocument(), "specSha256", p.specSha256()))
            .toList();
    }
}
