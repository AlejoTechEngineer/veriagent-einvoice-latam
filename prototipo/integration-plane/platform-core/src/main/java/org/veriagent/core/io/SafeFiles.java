package org.veriagent.core.io;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Entradas externas: nombres de archivo saneados (sin recorrido de rutas ni caracteres de control) y rutas
 * confinadas a un directorio base.
 */
public final class SafeFiles {
    private static final Pattern ALLOWED = Pattern.compile("[^A-Za-z0-9._-]");
    private static final Set<String> WINDOWS_RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
    private static final int MAX_LENGTH = 120;

    private SafeFiles() {}

    /**
     * Nombre de archivo seguro: se descarta cualquier ruta, los caracteres no permitidos se sustituyen por "_",
     * no puede empezar por punto y se rechazan los nombres reservados. Lanza IllegalArgumentException si no
     * queda un nombre utilizable.
     */
    public static String sanitize(String original) {
        if (original == null) throw new IllegalArgumentException("Nombre de archivo ausente");
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = ALLOWED.matcher(name).replaceAll("_");
        while (name.startsWith(".")) name = name.substring(1);
        if (name.length() > MAX_LENGTH) name = name.substring(name.length() - MAX_LENGTH);
        String stem = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        if (name.isBlank() || name.chars().allMatch(ch -> ch == '_' || ch == '.')
            || WINDOWS_RESERVED.contains(stem.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Nombre de archivo no permitido: " + original);
        }
        return name;
    }

    /** Resuelve el nombre dentro de la base y garantiza que el resultado no sale de ella. */
    public static Path resolveInside(Path base, String fileName) {
        Path root = base.toAbsolutePath().normalize();
        Path p = root.resolve(sanitize(fileName)).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("Ruta fuera del directorio permitido: " + fileName);
        return p;
    }
}
