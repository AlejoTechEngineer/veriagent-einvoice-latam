package org.veriagent.kit;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.tfg.kit.catalog.Catalog;
import org.tfg.kit.catalog.CatalogRegistry;
import org.tfg.kit.core.DefectType;
import org.tfg.kit.core.Finding;
import org.tfg.kit.core.FixtureComparator;
import org.tfg.kit.core.Mode;
import org.tfg.kit.core.Provenance;
import org.tfg.kit.core.SpecDocument;
import org.tfg.kit.core.VerificationReport;
import org.veriagent.contracts.ContractJson;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Perfil {@code gamma}: formato JSON del proveedor sintético Gamma (país XX). Estructura con JSON Schema
 * ({@code schemas/gamma-v1.schema.json}, equivalente al XSD de Alfa) y reglas D1 a D6 con procedencia: cada
 * hallazgo cita la sección de {@code specs/proveedor-gamma-v1.md} y su huella SHA-256.
 */
public final class GammaProfile implements KitVerifier.Profile {
    private static final BigDecimal TOL = new BigDecimal("0.01");
    private static final String VERSION = "1.0.0";
    private static final String SPEC_VERSION = "GAMMA-1";

    private final SpecDocument spec;
    private final CatalogRegistry catalogs;
    private final Schema schema;

    public GammaProfile(Path home) throws Exception {
        this.spec = SpecDocument.load(home.resolve("specs/proveedor-gamma-v1.md"));
        this.catalogs = CatalogRegistry.load(home.resolve("catalogs/catalogos-gamma-v1.csv"));
        this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(ContractJson.mapper().readTree(Files.readString(home.resolve("schemas/gamma-v1.schema.json"))));
    }

    @Override public String id() { return "gamma"; }
    @Override public String format() { return "json"; }
    @Override public String specDocument() { return spec.name(); }
    @Override public String specSha256() { return spec.sha256(); }

    private Provenance p(String section) { return Provenance.of(spec, section, VERSION); }

    @Override
    public VerificationReport verify(String id, String content, Mode mode, String expected) {
        VerificationReport r = new VerificationReport(id, mode.name());
        JsonNode doc;
        try {
            doc = ContractJson.mapper().readTree(content);
        } catch (RuntimeException e) {
            r.add(new Finding("G-E0-01", DefectType.E0, "documento", "No es JSON válido", p("§G.1")));
            return r;
        }
        for (Error e : schema.validate(doc)) r.add(structural(e));
        if (mode == Mode.SCHEMA_ONLY || !doc.isObject()) return r;
        rules(doc, r);
        if (mode == Mode.CI && expected != null) {
            r.addAll(FixtureComparator.compare(leaves(ContractJson.mapper().readTree(expected), ""), leaves(doc, ""),
                p("fixture validado")));
        }
        return r;
    }

    /** Clasificación de los errores de estructura: requerido ausente (D4), campo retirado (D5), otros (E0). */
    private Finding structural(Error e) {
        String key = e.getMessageKey() == null ? "" : e.getMessageKey();
        String msg = e.getMessage();
        if (key.startsWith("required")) return new Finding("G-XSD-01", DefectType.D4, e.getInstanceLocation().toString(), msg, p("§G.2"));
        if (key.startsWith("additionalProperties") && msg.contains("legacyRef")) {
            return new Finding("G-XSD-02", DefectType.D5, "/header/legacyRef", "Campo retirado en la versión 1", p("§G.7"));
        }
        return new Finding("G-XSD-00", DefectType.E0, e.getInstanceLocation().toString(), msg, p("§G.1"));
    }

    private void rules(JsonNode d, VerificationReport r) {
        JsonNode h = d.path("header");
        String sv = text(h, "specVersion");
        if (sv != null && !SPEC_VERSION.equals(sv)) {
            r.add(new Finding("G-D3-01", DefectType.D3, "/header/specVersion", "Se esperaba exactamente '" + SPEC_VERSION + "' y llegó '" + sv + "'", p("§G.1")));
        }
        String cur = text(h, "currency");
        if (cur != null && !catalogs.get("Moneda").contains(cur)) {
            r.add(new Finding("G-D3-02", DefectType.D3, "/header/currency", "Moneda fuera del catálogo o mal escrita: '" + cur + "'", p("§G.2.2")));
        }
        String dt = text(h, "documentType");
        Catalog tipo = catalogs.get("TipoDocumento");
        if (dt != null && !tipo.contains(dt)) {
            r.add(new Finding("G-D1-01", DefectType.D1, "/header/documentType", "Código '" + dt + "' no existe en TipoDocumento v" + tipo.version(), p("§G.2.1")));
        } else if (dt != null) {
            boolean hasOrig = text(h, "originalNumber") != null;
            if ("FAC".equals(dt) && hasOrig) r.add(new Finding("G-D1-04", DefectType.D1, "/header/originalNumber",
                "Una factura (FAC) no debe llevar originalNumber: el tipo parece incorrecto", p("§G.2.1")));
            if (!"FAC".equals(dt) && !hasOrig) r.add(new Finding("G-D1-04", DefectType.D1, "/header/documentType",
                "El tipo " + dt + " exige originalNumber y no lo trae: el tipo parece incorrecto", p("§G.2.1")));
        }
        Catalog ids = catalogs.get("TipoIdentificacion");
        for (String party : List.of("seller", "buyer")) {
            JsonNode pn = d.path("parties").path(party);
            String scheme = text(pn, "idScheme"), taxId = text(pn, "taxId");
            if (scheme == null || taxId == null) continue;
            Optional<Catalog.Entry> e = ids.get(scheme);
            if (e.isEmpty()) {
                r.add(new Finding("G-D1-02", DefectType.D1, "/parties/" + party + "/idScheme", "Código '" + scheme + "' no existe en TipoIdentificacion", p("§G.3.1")));
            } else if (!Pattern.matches(e.get().extra(), taxId) || ("TX".equals(scheme) && !luhn(taxId))) {
                r.add(new Finding("G-D1-02", DefectType.D1, "/parties/" + party + "/taxId", "El valor no cumple el formato del tipo " + scheme, p("§G.3.1")));
            }
        }
        for (String[] req : new String[][]{{"/header/number", "§G.2"}, {"/header/issueDate", "§G.2"},
            {"/parties/seller/name", "§G.3"}, {"/parties/buyer/name", "§G.3"}, {"/parties/buyer/email", "§G.3"}}) {
            JsonNode v = d.at(req[0]);
            if (v.isMissingNode() || v.isNull() || v.asString("").isBlank()) {
                r.add(new Finding("G-D4-01", DefectType.D4, req[0], "Campo obligatorio ausente o vacío", p(req[1])));
            }
        }
        reserved(d, r);
        Catalog tax = catalogs.get("Impuesto");
        BigDecimal sumNet = BigDecimal.ZERO, sumTax = BigDecimal.ZERO;
        int n = 0;
        for (JsonNode l : d.path("lines")) {
            n++;
            String loc = "/lines[" + n + "]";
            BigDecimal q = dec(l, "qty"), pr = dec(l, "unitPrice"), net = dec(l, "net"), rate = dec(l, "taxRate");
            String code = text(l, "taxCode");
            if (code != null) {
                Optional<Catalog.Entry> e = tax.get(code);
                if (e.isEmpty()) {
                    r.add(new Finding("G-D1-03", DefectType.D1, loc + "/taxCode", "Código de impuesto '" + code + "' no existe", p("§G.4.1")));
                } else if (rate != null && rate.compareTo(new BigDecimal(e.get().extra())) != 0) {
                    r.add(new Finding("G-D1-03", DefectType.D1, loc + "/taxRate", "La tarifa " + rate + " no corresponde al código " + code, p("§G.4.1")));
                }
            }
            if (q != null && pr != null && net != null && q.multiply(pr).subtract(net).abs().compareTo(TOL) > 0) {
                r.add(new Finding("G-D2-01", DefectType.D2, loc, "qty × unitPrice ≠ net", p("§G.4")));
            }
            if (net != null) {
                sumNet = sumNet.add(net);
                if (rate != null) sumTax = sumTax.add(net.multiply(rate).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
            }
        }
        JsonNode t = d.path("totals");
        BigDecimal tn = dec(t, "net"), tt = dec(t, "tax"), tg = dec(t, "gross");
        if (tn != null && tn.subtract(sumNet).abs().compareTo(TOL) > 0) r.add(new Finding("G-D2-02", DefectType.D2, "/totals/net", "La base no es la suma de las líneas", p("§G.5")));
        if (tt != null && tt.subtract(sumTax).abs().compareTo(TOL) > 0) r.add(new Finding("G-D2-03", DefectType.D2, "/totals/tax", "El impuesto no coincide con Σ net × tarifa", p("§G.5")));
        if (tn != null && tt != null && tg != null && tn.add(tt).subtract(tg).abs().compareTo(TOL) > 0) {
            r.add(new Finding("G-D2-02", DefectType.D2, "/totals/gross", "gross ≠ net + tax", p("§G.5")));
        }
    }

    /** D6: el separador reservado "|" no puede aparecer en textos libres. */
    private void reserved(JsonNode d, VerificationReport r) {
        List<String> texts = new ArrayList<>();
        texts.add("/parties/seller/name");
        texts.add("/parties/buyer/name");
        int n = d.path("lines").size();
        for (int i = 0; i < n; i++) texts.add("/lines/" + i + "/description");
        for (String ptr : texts) {
            String v = d.at(ptr).asString("");
            if (v.contains("|")) r.add(new Finding("G-D6-01", DefectType.D6, ptr, "Texto libre con el carácter reservado '|' sin sanear", p("§G.6")));
        }
    }

    private static boolean luhn(String s) {
        int sum = 0;
        boolean dbl = false;
        for (int i = s.length() - 1; i >= 0; i--) {
            int x = s.charAt(i) - '0';
            if (dbl) { x *= 2; if (x > 9) x -= 9; }
            sum += x;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static BigDecimal dec(JsonNode n, String f) {
        String v = text(n, f);
        if (v == null || v.isBlank()) return null;
        try { return new BigDecimal(v.trim()); } catch (NumberFormatException e) { return null; }
    }

    /** Hojas "ruta=valor" en orden de documento, para comparar con el ejemplo validado. */
    static List<String> leaves(JsonNode n, String path) {
        List<String> out = new ArrayList<>();
        if (n.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = n.properties().iterator();
            while (it.hasNext()) {
                var e = it.next();
                out.addAll(leaves(e.getValue(), path + "/" + e.getKey()));
            }
        } else if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) out.addAll(leaves(n.get(i), path + "/" + i));
        } else {
            out.add(path + "=" + n.asString());
        }
        return out;
    }
}
