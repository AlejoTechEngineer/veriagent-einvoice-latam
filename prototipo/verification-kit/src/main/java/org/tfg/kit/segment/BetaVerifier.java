package org.tfg.kit.segment;

import org.tfg.kit.catalog.CatalogRegistry;
import org.tfg.kit.core.DefectType;
import org.tfg.kit.core.Finding;
import org.tfg.kit.core.Provenance;
import org.tfg.kit.core.SpecDocument;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Verificador del formato por segmentos del proveedor Beta (§B). Un separador sin sanear en un texto
 * libre desplaza los campos: se detecta por conteo y por la posición de los valores numéricos.
 */
public final class BetaVerifier {
    private static final Map<String, Integer> FIELDS = Map.of("ENC", 8, "DET", 7, "TOT", 4);
    private final CatalogRegistry catalogs;
    private final SpecDocument spec;

    public BetaVerifier(CatalogRegistry catalogs, SpecDocument spec) { this.catalogs = catalogs; this.spec = spec; }

    private Provenance p(String s) { return Provenance.of(spec, s, "2.1.0"); }

    /** Validación estructural: segmentos conocidos y número de campos. */
    public List<Finding> structure(String text) {
        List<Finding> out = new ArrayList<>();
        String[] lines = text.strip().split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String[] f = lines[i].split("\\|", -1);
            Integer expected = FIELDS.get(f[0]);
            if (expected == null) {
                out.add(new Finding("S-E0-01", DefectType.E0, "segmento " + (i + 1), "Segmento desconocido: " + f[0], p("§B.2")));
            } else if (f.length != expected) {
                out.add(new Finding("R-D6-01", DefectType.D6, "segmento " + (i + 1) + " (" + f[0] + ")",
                    "Se esperaban " + expected + " campos y hay " + f.length + ": probable separador reservado sin sanear", p("§B.1")));
            }
        }
        return out;
    }

    /** Reglas de negocio sobre segmentos bien formados. */
    public List<Finding> rules(String text) {
        List<Finding> out = new ArrayList<>();
        for (String line : text.strip().split("\\R")) {
            String[] f = line.split("\\|", -1);
            if ("ENC".equals(f[0]) && f.length == 8 && !catalogs.get("TipoDocumento").contains(f[3])) {
                out.add(new Finding("R-D1-01", DefectType.D1, "ENC/tipo", "Código de tipo inexistente: " + f[3], p("§3.2")));
            }
            if ("DET".equals(f[0]) && f.length == 7) {
                try {
                    BigDecimal q = new BigDecimal(f[3]), pr = new BigDecimal(f[4]), ext = new BigDecimal(f[5]);
                    if (q.multiply(pr).subtract(ext).abs().compareTo(new BigDecimal("0.01")) > 0) {
                        out.add(new Finding("R-D2-01", DefectType.D2, "DET " + f[1], "Cantidad × precio ≠ valor de línea", p("§5.3")));
                    }
                } catch (NumberFormatException e) {
                    out.add(new Finding("R-D6-02", DefectType.D6, "DET " + f[1], "Campo numérico no numérico: posible desplazamiento", p("§B.1")));
                }
                if (!catalogs.get("Tributo").contains(f[6])) {
                    out.add(new Finding("R-D1-03", DefectType.D1, "DET " + f[1], "Código de tributo inexistente: " + f[6], p("§5.1")));
                }
            }
        }
        return out;
    }
}
