package org.veriagent.core.correlation;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * Identificador de correlación del hilo actual. Viaja por las peticiones HTTP, los eventos, la base de datos
 * y los registros (MDC), de modo que un comprobante se puede seguir de punta a punta.
 */
public final class CorrelationContext {
    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CorrelationContext() {}

    /** Correlación actual; si no hay ninguna, crea una nueva para no perder el rastro. */
    public static String current() {
        String id = CURRENT.get();
        return id != null ? id : newId();
    }

    public static String newId() { return UUID.randomUUID().toString(); }

    /** Acepta solo identificadores seguros para registros y cabeceras; si no, genera uno nuevo. */
    public static String sanitize(String candidate) {
        return candidate != null && VALID.matcher(candidate).matches() ? candidate : newId();
    }

    public static void set(String id) {
        CURRENT.set(id);
        MDC.put(MDC_KEY, id);
    }

    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_KEY);
    }

    /** Ejecuta con una correlación fija y restaura la anterior al terminar. */
    public static <T> T call(String id, Callable<T> body) throws Exception {
        String previous = CURRENT.get();
        set(id);
        try {
            return body.call();
        } finally {
            if (previous == null) clear(); else set(previous);
        }
    }

    public static void run(String id, Runnable body) {
        try {
            call(id, () -> { body.run(); return null; });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
