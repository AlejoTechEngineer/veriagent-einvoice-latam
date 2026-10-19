package org.tfg.kit.catalog;

import java.util.Map;
import java.util.Optional;

/** Catálogo oficial versionado: código -> entrada. */
public record Catalog(String name, String version, String section, Map<String, Entry> entries) {
    public record Entry(String code, String label, String extra) {}
    public boolean contains(String code) { return entries.containsKey(code); }
    public Optional<Entry> get(String code) { return Optional.ofNullable(entries.get(code)); }
}
