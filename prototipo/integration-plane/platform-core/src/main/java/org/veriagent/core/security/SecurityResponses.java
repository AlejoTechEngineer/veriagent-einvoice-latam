package org.veriagent.core.security;

import jakarta.servlet.http.HttpServletResponse;
import org.veriagent.contracts.ContractJson;
import org.veriagent.core.correlation.CorrelationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Respuestas JSON uniformes para 401 y 403, en español. */
final class SecurityResponses {
    private SecurityResponses() {}

    static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        var body = ContractJson.mapper().createObjectNode()
            .put("status", status).put("code", code).put("message", message)
            .put("correlationId", CorrelationContext.current());
        response.getWriter().write(ContractJson.mapper().writeValueAsString(body));
    }
}
