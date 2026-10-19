package org.tfg.kit.catalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Carga catálogos versionados desde CSV. Cada catálogo conserva la sección normativa de origen. */
public final class CatalogRegistry {
    private final Map<String, Catalog> catalogs = new LinkedHashMap<>();

    public static CatalogRegistry load(Path csv) throws IOException {
        CatalogRegistry r = new CatalogRegistry();
        Map<String, Map<String, Catalog.Entry>> tmp = new LinkedHashMap<>();
        Map<String, String[]> meta = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        boolean header = true;
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            if (header) { header = false; continue; }
            String[] p = line.split(",", -1);
            if (p.length < 6) throw new IOException("Fila de catálogo inválida: " + line);
            String extra = p[0].equals("TipoIdentificacion") ? p[4].replace(';', ',') : p[4];
            tmp.computeIfAbsent(p[0], k -> new LinkedHashMap<>()).put(p[2], new Catalog.Entry(p[2], p[3], extra));
            meta.putIfAbsent(p[0], new String[]{p[1], p[5]});
        }
        tmp.forEach((name, entries) -> r.catalogs.put(name,
            new Catalog(name, meta.get(name)[0], meta.get(name)[1], Map.copyOf(entries))));
        return r;
    }

    public Catalog get(String name) {
        Catalog c = catalogs.get(name);
        if (c == null) throw new IllegalArgumentException("Catálogo desconocido: " + name);
        return c;
    }
}
