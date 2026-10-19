package org.tfg.kit.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Comparación con fixtures validados por personas (gate de CI). Detecta defectos que ninguna regla
 * en ejecución puede ver, como dos campos intercambiados cuyo producto es el mismo.
 */
public final class FixtureComparator {
    private FixtureComparator() {}

    public static List<Finding> compare(List<String> expected, List<String> actual, Provenance prov) {
        List<Finding> out = new ArrayList<>();
        int n = Math.max(expected.size(), actual.size());
        for (int i = 0; i < n; i++) {
            String e = i < expected.size() ? expected.get(i) : "<ausente>";
            String a = i < actual.size() ? actual.get(i) : "<ausente>";
            if (!e.equals(a)) {
                out.add(new Finding("CI-FX-01", DefectType.E0, "posición " + (i + 1),
                    "Difiere del fixture validado. Esperado: " + e + " | Obtenido: " + a, prov));
                if (out.size() >= 5) break;
            }
        }
        return out;
    }
}
