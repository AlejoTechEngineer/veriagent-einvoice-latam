package org.veriagent.gateway;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.veriagent.core.security.Actor;
import org.veriagent.core.security.CurrentActor;
import org.veriagent.core.web.ApiErrors;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * API de borde (BFF) del prototipo, dentro de la misma aplicación Boot (D-03): sesión del usuario, proxy hacia la
 * API de revisión de propuestas de IA (Python) y el front web estático. El proxy solo reenvía rutas de una lista
 * blanca y siempre con el token del usuario: la API de revisión vuelve a validar el token y el rol.
 */
@RestController
@Tag(name = "API de borde", description = "Sesión y acceso unificado a la API de revisión de propuestas")
@ConditionalOnProperty(prefix = "veriagent.services", name = "gateway", havingValue = "true", matchIfMissing = true)
public class GatewayController {
    static final int MAX_BODY = 1024 * 1024;
    static final Pattern ALLOWED = Pattern.compile("^/api/(health|me|proposals|proposals/prop-[a-f0-9]{8}(/decision)?)$");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String agentsUrl;

    public GatewayController(@Value("${veriagent.gateway.agents-url:http://127.0.0.1:8090}") String agentsUrl) {
        this.agentsUrl = agentsUrl.replaceAll("/+$", "");
    }

    @Operation(summary = "Identidad y rol del token de la sesión")
    @GetMapping("/api/session")
    public Map<String, Object> session() {
        Actor a = CurrentActor.get();
        return Map.of("subject", a.subject(), "role", a.role().name());
    }

    @Operation(summary = "Consulta a la API de revisión de propuestas (lista blanca de rutas)")
    @GetMapping("/agents/api/**")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> get(HttpServletRequest request) throws IOException, InterruptedException {
        return forward(request, "GET", null);
    }

    @Operation(summary = "Decisión sobre una propuesta, reenviada con el token del usuario (la API de revisión exige REVIEWER)")
    @PostMapping("/agents/api/**")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> post(HttpServletRequest request) throws IOException, InterruptedException {
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY + 1);
        }
        if (body.length > MAX_BODY) return ApiErrors.body(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Cuerpo demasiado grande", null);
        return forward(request, "POST", body);
    }

    private ResponseEntity<?> forward(HttpServletRequest request, String method, byte[] body) throws IOException, InterruptedException {
        String path = request.getRequestURI().substring(request.getContextPath().length() + "/agents".length());
        if (!ALLOWED.matcher(path).matches() || request.getQueryString() != null) {
            return ApiErrors.body(HttpStatus.NOT_FOUND, "NOT_FOUND", "Ruta no disponible en la API de borde", null);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(agentsUrl + path)).timeout(Duration.ofSeconds(60))
            .header("Authorization", request.getHeader("Authorization"));
        if (body == null) b.GET();
        else b.header("Content-Type", MediaType.APPLICATION_JSON_VALUE).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            return ResponseEntity.status(r.statusCode()).contentType(MediaType.APPLICATION_JSON).body(r.body());
        } catch (java.net.ConnectException e) {
            return ApiErrors.body(HttpStatus.BAD_GATEWAY, "AGENTS_UNAVAILABLE",
                "La API de revisión de propuestas no está en marcha (scripts\\run-local.ps1 la arranca)", null);
        }
    }
}
