package org.veriagent.core.retry;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.veriagent.core.VeriagentProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Reintentos con espera creciente (exponencial con tope), sobre Resilience4j. Solo se reintentan los errores
 * recuperables: los rechazos del proveedor o del kit no se reintentan, van directamente a FAILED o a la DLQ.
 */
public class RetryPolicies {
    private final VeriagentProperties.Retry cfg;

    public RetryPolicies(VeriagentProperties props) {
        this.cfg = props.getRetry();
    }

    /** Política para llamadas a proveedores: reintenta solo {@link RecoverableException}. */
    public Retry providerCalls(String name) {
        return build(name, e -> e instanceof RecoverableException);
    }

    public Retry build(String name, Predicate<Throwable> retryOn) {
        RetryConfig config = RetryConfig.custom()
            .maxAttempts(cfg.getMaxAttempts())
            .intervalFunction(interval())
            .retryOnException(retryOn)
            .build();
        return Retry.of(name, config);
    }

    public IntervalFunction interval() {
        return IntervalFunction.ofExponentialBackoff(cfg.getInitialInterval(), cfg.getMultiplier(), cfg.getMaxInterval());
    }

    /** Esperas que se aplicarán entre intentos (para documentación y pruebas). */
    public List<Duration> delays() {
        List<Duration> out = new ArrayList<>();
        IntervalFunction f = interval();
        for (int attempt = 1; attempt < cfg.getMaxAttempts(); attempt++) out.add(Duration.ofMillis(f.apply(attempt)));
        return out;
    }

    /** Error transitorio (indisponibilidad, tiempo agotado): se puede reintentar. */
    public static class RecoverableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public RecoverableException(String message, Throwable cause) {
            super(message, cause);
        }

        public RecoverableException(String message) {
            super(message);
        }
    }
}
