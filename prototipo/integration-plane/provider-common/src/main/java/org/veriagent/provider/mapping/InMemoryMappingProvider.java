package org.veriagent.provider.mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proveedor de mapeos en memoria, sembrado con los mapeos de referencia aprobados. Lo usan las pruebas y el
 * arranque; el registro de mapeos (svc-mapping-registry) lo sustituye con versiones persistidas.
 */
public class InMemoryMappingProvider implements MappingProvider {
    private final Map<String, List<MappingDocument>> versions = new ConcurrentHashMap<>();
    private final Map<String, Integer> current = new ConcurrentHashMap<>();

    private static String key(String provider, String country) { return provider + "/" + country; }

    /** Añade una versión y la deja vigente. */
    public synchronized void publishAndActivate(MappingDocument d) {
        versions.computeIfAbsent(key(d.provider(), d.country()), k -> new ArrayList<>()).add(d);
        current.put(key(d.provider(), d.country()), d.version());
    }

    public synchronized void activate(String provider, String country, int version) {
        boolean exists = versions.getOrDefault(key(provider, country), List.of()).stream().anyMatch(d -> d.version() == version);
        if (!exists) throw new IllegalArgumentException("No existe la versión " + version + " de " + key(provider, country));
        current.put(key(provider, country), version);
    }

    @Override
    public Optional<MappingDocument> current(String provider, String country) {
        Integer v = current.get(key(provider, country));
        if (v == null) return Optional.empty();
        return versions.get(key(provider, country)).stream().filter(d -> d.version() == v).findFirst();
    }

    @Override
    public List<MappingDocument> versions(String provider, String country) {
        return List.copyOf(versions.getOrDefault(key(provider, country), List.of()));
    }
}
