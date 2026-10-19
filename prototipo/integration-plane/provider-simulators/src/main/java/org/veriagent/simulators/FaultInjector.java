package org.veriagent.simulators;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fallos inyectables por proveedor. Se configuran por la API de administración (rol OPERATOR) o, por petición,
 * con la cabecera {@code X-Sim-Fault}. Cada fallo configurado se aplica a las próximas {@code remaining} llamadas
 * ({@code -1}: hasta que se retire).
 */
public class FaultInjector {

    public enum Fault {
        /** El proveedor responde 503. */
        UNAVAILABLE,
        /** El proveedor tarda más que el tiempo de espera del cliente. */
        SLOW,
        /** El proveedor entrega el mismo resultado dos veces. */
        DUPLICATE,
        /** El proveedor actualizó su catálogo y rechaza los comprobantes (escenario S4). */
        REJECT_CATALOG,
        /** El proveedor procesa pero su respuesta se pierde; solo queda la constancia oficial (ADR-06). */
        LOST_RESPONSE;

        public static Fault parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Fallo desconocido: " + value
                    + " (válidos: unavailable, slow, duplicate, reject-catalog, lost-response)");
            }
        }
    }

    public record Active(Fault fault, int remaining, String setBy) {}

    private final Map<String, Active> active = new ConcurrentHashMap<>();

    public void set(String provider, Fault fault, int remaining, String setBy) {
        if (remaining == 0 || remaining < -1) throw new IllegalArgumentException("remaining debe ser -1 o mayor que 0");
        active.put(provider, new Active(fault, remaining, setBy));
    }

    public void clear(String provider) {
        active.remove(provider);
    }

    public Map<String, Active> snapshot() {
        return Map.copyOf(active);
    }

    /** Fallo que aplica a esta llamada: la cabecera manda; si no, el configurado (y se descuenta). */
    public Optional<Fault> next(String provider, String header) {
        if (header != null && !header.isBlank()) return Optional.of(Fault.parse(header));
        Active[] used = new Active[1];
        active.computeIfPresent(provider, (k, a) -> {
            used[0] = a;
            if (a.remaining() == -1) return a;
            return a.remaining() > 1 ? new Active(a.fault(), a.remaining() - 1, a.setBy()) : null;
        });
        return Optional.ofNullable(used[0]).map(Active::fault);
    }
}
