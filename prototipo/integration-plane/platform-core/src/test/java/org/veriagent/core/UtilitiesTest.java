package org.veriagent.core;

import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veriagent.core.io.SafeFiles;
import org.veriagent.core.retry.RetryPolicies;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class UtilitiesTest {

    private static RetryPolicies policies() {
        VeriagentProperties p = new VeriagentProperties();
        p.getRetry().setMaxAttempts(5);
        p.getRetry().setInitialInterval(Duration.ofMillis(10));
        p.getRetry().setMultiplier(2.0);
        p.getRetry().setMaxInterval(Duration.ofMillis(60));
        return new RetryPolicies(p);
    }

    @Test
    @DisplayName("Reintentos: espera creciente con tope")
    void exponentialBackoff() {
        assertEquals(List.of(Duration.ofMillis(10), Duration.ofMillis(20), Duration.ofMillis(40), Duration.ofMillis(60)),
            policies().delays());
    }

    @Test
    @DisplayName("Reintentos: solo los errores recuperables, hasta el máximo de intentos")
    void retriesOnlyRecoverable() {
        Retry retry = policies().providerCalls("prueba");
        AtomicInteger calls = new AtomicInteger();
        assertThrows(RetryPolicies.RecoverableException.class, () -> Retry.decorateRunnable(retry, () -> {
            calls.incrementAndGet();
            throw new RetryPolicies.RecoverableException("caído");
        }).run());
        assertEquals(5, calls.get());

        AtomicInteger rejected = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> Retry.decorateRunnable(retry, () -> {
            rejected.incrementAndGet();
            throw new IllegalStateException("rechazo definitivo");
        }).run());
        assertEquals(1, rejected.get());
    }

    @Test
    @DisplayName("Nombres de archivo: sin recorrido de rutas, sin caracteres peligrosos y sin nombres reservados")
    void safeFileNames() {
        assertEquals("passwd", SafeFiles.sanitize("../../etc/passwd"));
        assertEquals("lote_1.json", SafeFiles.sanitize("C:\\datos\\lote 1.json"));
        assertEquals("archivo.zip", SafeFiles.sanitize("...archivo.zip"));
        assertEquals("a_b_c.json", SafeFiles.sanitize("a<b>c.json"));
        assertThrows(IllegalArgumentException.class, () -> SafeFiles.sanitize(".."));
        assertThrows(IllegalArgumentException.class, () -> SafeFiles.sanitize("CON.json"));
        assertThrows(IllegalArgumentException.class, () -> SafeFiles.sanitize(null));
        assertThrows(IllegalArgumentException.class, () -> SafeFiles.sanitize("/"));
        Path base = Path.of("buzon").toAbsolutePath();
        assertTrue(SafeFiles.resolveInside(base, "../../fuera.json").startsWith(base));
        assertEquals(120, SafeFiles.sanitize("x".repeat(500) + ".json").length());
    }
}
