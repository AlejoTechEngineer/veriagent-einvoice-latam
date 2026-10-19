package org.veriagent.responsepublisher;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Entregas de respuestas al sistema de origen (solo lectura). */
@RestController
@RequestMapping("/api/publisher")
@Tag(name = "Publicador de respuestas")
@ConditionalOnProperty(prefix = "veriagent.services", name = "response-publisher", havingValue = "true", matchIfMissing = true)
public class PublisherController {
    private final JdbcClient jdbc;

    public PublisherController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Operation(summary = "Últimas entregas de respuestas")
    @GetMapping("/deliveries")
    public List<Map<String, Object>> deliveries(@RequestParam(defaultValue = "50") int limit) {
        return jdbc.sql("SELECT * FROM response_delivery ORDER BY id DESC LIMIT :n").param("n", Math.max(1, Math.min(limit, 500)))
            .query().listOfRows();
    }
}
