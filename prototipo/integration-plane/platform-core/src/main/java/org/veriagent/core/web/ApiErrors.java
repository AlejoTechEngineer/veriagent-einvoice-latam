package org.veriagent.core.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.veriagent.core.bus.Events;
import org.veriagent.core.correlation.CorrelationContext;
import org.veriagent.core.security.IdentityGuard;
import org.veriagent.core.state.IllegalTransitionException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Errores de la API en formato JSON uniforme y en español, con el identificador de correlación. */
@RestControllerAdvice
public class ApiErrors {

    public static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message, List<String> details) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status.value());
        m.put("code", code);
        m.put("message", message);
        if (details != null && !details.isEmpty()) m.put("details", details);
        m.put("correlationId", CorrelationContext.current());
        return ResponseEntity.status(status).body(m);
    }

    @ExceptionHandler(IllegalTransitionException.class)
    public ResponseEntity<Map<String, Object>> transition(IllegalTransitionException e) {
        return body(HttpStatus.CONFLICT, "ILLEGAL_TRANSITION", e.getMessage(), null);
    }

    @ExceptionHandler(IdentityGuard.ImpersonationException.class)
    public ResponseEntity<Map<String, Object>> impersonation(IdentityGuard.ImpersonationException e) {
        return body(HttpStatus.FORBIDDEN, "IMPERSONATION", e.getMessage(), null);
    }

    @ExceptionHandler(Events.ContractViolationException.class)
    public ResponseEntity<Map<String, Object>> contract(Events.ContractViolationException e) {
        return body(HttpStatus.BAD_REQUEST, "CONTRACT_VIOLATION", "El documento no cumple el contrato", e.errors());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "El archivo supera el tamaño máximo permitido", null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage()).toList();
        return body(HttpStatus.BAD_REQUEST, "VALIDATION", "La petición no es válida", details);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoSuchElementException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage() == null ? "No encontrado" : e.getMessage(), null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage(), null);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
        HttpStatus s = HttpStatus.valueOf(e.getStatusCode().value());
        return body(s, s.name(), e.getReason() == null ? s.getReasonPhrase() : e.getReason(), null);
    }
}
