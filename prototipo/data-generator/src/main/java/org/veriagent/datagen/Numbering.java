package org.veriagent.datagen;

import org.veriagent.contracts.model.Country;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Configuración de numeración por país leída de {@code data/numeracion/*.csv}
 * (columnas: pais,prefijo,desde,hasta,vigencia_desde,vigencia_hasta,fuente).
 */
public final class Numbering {
    public record Range(Country country, String prefix, long from, long to, LocalDate validFrom, LocalDate validTo, String source) {
        /** Número del comprobante para la posición {@code seq} del rango (base 0). */
        public String number(long seq) {
            long n = from + seq;
            if (n > to) throw new IllegalStateException("Rango de numeración agotado para " + country + " (" + prefix + ")");
            return switch (country) {
                case PE -> prefix + "-" + String.format("%08d", n);
                default -> prefix + n;
            };
        }
    }

    private final Map<Country, Range> ranges;

    private Numbering(Map<Country, Range> ranges) {
        this.ranges = ranges;
    }

    public static Numbering load(Path dir) {
        Map<Country, Range> out = new EnumMap<>(Country.class);
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".csv")).sorted().toList()) {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                for (String line : lines.subList(1, lines.size())) {
                    if (line.isBlank()) continue;
                    String[] p = split(line);
                    Country c = Country.valueOf(p[0]);
                    out.put(c, new Range(c, p[1], Long.parseLong(p[2]), Long.parseLong(p[3]),
                        LocalDate.parse(p[4]), LocalDate.parse(p[5]), p[6]));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer la numeración de " + dir, e);
        }
        return new Numbering(out);
    }

    public Range range(Country c) {
        Range r = ranges.get(c);
        if (r == null) throw new IllegalArgumentException("Sin numeración configurada para " + c);
        return r;
    }

    /** CSV con la fuente entre comillas (puede contener comas). */
    private static String[] split(String line) {
        String[] head = line.split(",", 7);
        if (head.length == 7 && head[6].startsWith("\"") && head[6].endsWith("\"")) {
            head[6] = head[6].substring(1, head[6].length() - 1);
        }
        return head;
    }
}
