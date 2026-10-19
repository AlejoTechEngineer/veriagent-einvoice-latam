package org.tfg.kit.xml;

import org.tfg.kit.catalog.Catalog;
import org.tfg.kit.catalog.CatalogRegistry;
import org.tfg.kit.core.DefectType;
import org.tfg.kit.core.Finding;
import org.tfg.kit.core.Provenance;
import org.tfg.kit.core.SpecDocument;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reglas de negocio del perfil Alfa v2.1. Cada regla declara su tipo de defecto (D1 a D5)
 * y su procedencia: sección de la especificación y hash del documento fuente.
 */
public final class AlfaRules {
    private static final BigDecimal TOL = new BigDecimal("0.01");
    private static final String PROFILE = "PerfilAlfa-2.1";
    private static final String RULE_VERSION = "2.1.0";

    private final CatalogRegistry catalogs;
    private final SpecDocument spec;

    public AlfaRules(CatalogRegistry catalogs, SpecDocument spec) { this.catalogs = catalogs; this.spec = spec; }

    private Provenance p(String section) { return Provenance.of(spec, section, RULE_VERSION); }

    public List<Finding> check(Document doc) {
        List<Finding> out = new ArrayList<>();
        Element inv = doc.getDocumentElement();
        if (!"Invoice".equals(inv.getLocalName())) {
            out.add(new Finding("R-E0-01", DefectType.E0, "raíz", "La raíz no es Invoice", p("§6")));
            return out;
        }
        literals(inv, out);
        documentType(inv, out);
        parties(inv, out);
        required(inv, out);
        retired(inv, out);
        amounts(inv, out);
        return out;
    }

    /* D3: literales exactos y listas cerradas. */
    private void literals(Element inv, List<Finding> out) {
        String profile = Dom.value(inv, "ProfileID");
        if (profile != null && !PROFILE.equals(profile)) {
            out.add(new Finding("R-D3-01", DefectType.D3, "Invoice/ProfileID",
                "Se esperaba exactamente '" + PROFILE + "' y llegó '" + profile + "'", p("§2.1")));
        }
        String cur = Dom.value(inv, "DocumentCurrencyCode");
        if (cur != null && !catalogs.get("Moneda").contains(cur)) {
            out.add(new Finding("R-D3-03", DefectType.D3, "Invoice/DocumentCurrencyCode",
                "Moneda fuera de la lista cerrada o mal escrita: '" + cur + "'", p("§2.3")));
        }
    }

    /* D1: tipo de documento contra catálogo y coherencia con BillingReference. */
    private void documentType(Element inv, List<Finding> out) {
        String code = Dom.value(inv, "InvoiceTypeCode");
        if (code == null) return;
        Catalog cat = catalogs.get("TipoDocumento");
        if (!cat.contains(code)) {
            out.add(new Finding("R-D1-01", DefectType.D1, "Invoice/InvoiceTypeCode",
                "Código '" + code + "' no existe en TipoDocumento v" + cat.version(), p(cat.section())));
            return;
        }
        boolean hasRef = Dom.value(inv, "BillingReference") != null;
        if ("01".equals(code) && hasRef) {
            out.add(new Finding("R-D1-04", DefectType.D1, "Invoice/BillingReference",
                "Una factura (01) no debe llevar BillingReference: el código de tipo parece incorrecto", p("§3.4")));
        }
        if (("91".equals(code) || "92".equals(code)) && !hasRef) {
            out.add(new Finding("R-D1-04", DefectType.D1, "Invoice/InvoiceTypeCode",
                "El tipo " + code + " exige BillingReference y no la trae: el código de tipo parece incorrecto", p("§3.4")));
        }
    }

    /* D1: tipo de identificación contra catálogo y formato del número. */
    private void parties(Element inv, List<Finding> out) {
        Catalog cat = catalogs.get("TipoIdentificacion");
        for (String party : List.of("AccountingSupplierParty", "AccountingCustomerParty")) {
            String base = "Invoice/" + party + "/Party/CompanyID";
            Element root = inv;
            String scheme = Dom.value(root, party + "/Party/CompanyID/@schemeName");
            String id = Dom.value(root, party + "/Party/CompanyID");
            if (scheme == null || id == null) continue;
            Optional<Catalog.Entry> e = cat.get(scheme);
            if (e.isEmpty()) {
                out.add(new Finding("R-D1-02", DefectType.D1, base + "/@schemeName",
                    "Código '" + scheme + "' no existe en TipoIdentificacion v" + cat.version(), p(cat.section())));
            } else if (!Pattern.matches(e.get().extra(), id)) {
                out.add(new Finding("R-D1-02", DefectType.D1, base,
                    "El valor no cumple el formato del tipo " + scheme + " (" + e.get().label() + ")", p(cat.section())));
            }
        }
    }

    /* D4: cobertura de campos obligatorios no vacíos (el XSD no detecta un elemento vacío). */
    private void required(Element inv, List<Finding> out) {
        String[][] req = {
            {"AccountingCustomerParty/Party/ElectronicMail", "§4.3"},
            {"ID", "§2.2"}, {"IssueDate", "§2.2"},
            {"AccountingCustomerParty/Party/PartyName", "§6"},
            {"AccountingSupplierParty/Party/PartyName", "§6"}
        };
        for (String[] r : req) {
            String v = Dom.value(inv, r[0]);
            if (v == null || v.isBlank()) {
                out.add(new Finding("R-D4-01", DefectType.D4, "Invoice/" + r[0],
                    "Campo obligatorio ausente o vacío", p(r[1])));
            }
        }
    }

    /* D5: elementos retirados en la versión vigente. */
    private void retired(Element inv, List<Finding> out) {
        for (Catalog.Entry e : catalogs.get("Retirado").entries().values()) {
            String local = e.code().substring(e.code().lastIndexOf('/') + 1);
            if (!Dom.children(inv, local).isEmpty()) {
                out.add(new Finding("R-D5-01", DefectType.D5, e.code(),
                    "Elemento retirado en la versión 2; no debe enviarse", p("§7")));
            }
        }
    }

    /* D1 (tributo), D3 (nombre del esquema) y D2 (invariantes aritméticas). */
    private void amounts(Element inv, List<Finding> out) {
        Catalog trib = catalogs.get("Tributo");
        BigDecimal sumLines = BigDecimal.ZERO, sumTax = BigDecimal.ZERO;
        int n = 0;
        for (Element line : Dom.children(inv, "InvoiceLine")) {
            n++;
            String loc = "InvoiceLine[" + n + "]";
            BigDecimal q = Dom.decimal(line, "InvoicedQuantity");
            BigDecimal price = Dom.decimal(line, "PriceAmount");
            BigDecimal ext = Dom.decimal(line, "LineExtensionAmount");
            BigDecimal pct = Dom.decimal(line, "TaxCategory/Percent");
            String code = Dom.value(line, "TaxCategory/ID");
            String scheme = Dom.value(line, "TaxCategory/TaxSchemeName");
            if (code != null) {
                Optional<Catalog.Entry> e = trib.get(code);
                if (e.isEmpty()) {
                    out.add(new Finding("R-D1-03", DefectType.D1, loc + "/TaxCategory/ID",
                        "Código de tributo '" + code + "' no existe en Tributo v" + trib.version(), p(trib.section())));
                } else {
                    String[] ex = e.get().extra().split(";");
                    if (pct != null && pct.compareTo(new BigDecimal(ex[0])) != 0) {
                        out.add(new Finding("R-D1-03", DefectType.D1, loc + "/TaxCategory",
                            "La tarifa " + pct + " no corresponde al código " + code + " (" + ex[0] + ")", p(trib.section())));
                    }
                    if (scheme != null && !ex[1].equals(scheme)) {
                        out.add(new Finding("R-D3-02", DefectType.D3, loc + "/TaxCategory/TaxSchemeName",
                            "Se esperaba exactamente '" + ex[1] + "' y llegó '" + scheme + "'", p("§5.1")));
                    }
                }
            }
            if (q != null && price != null) {
                if (q.signum() <= 0 || price.signum() < 0) {
                    out.add(new Finding("R-D2-04", DefectType.D2, loc, "Cantidad no positiva o precio negativo", p("§5.3")));
                }
                if (ext != null && q.multiply(price).subtract(ext).abs().compareTo(TOL) > 0) {
                    out.add(new Finding("R-D2-01", DefectType.D2, loc,
                        "Cantidad × precio (" + q.multiply(price).setScale(2, RoundingMode.HALF_UP) + ") ≠ valor de línea (" + ext + ")", p("§5.3")));
                }
            }
            if (ext != null) {
                sumLines = sumLines.add(ext);
                if (pct != null) sumTax = sumTax.add(ext.multiply(pct).divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
            }
        }
        BigDecimal base = Dom.decimal(inv, "LegalMonetaryTotal/LineExtensionAmount");
        BigDecimal tax = Dom.decimal(inv, "TaxTotal/TaxAmount");
        BigDecimal pay = Dom.decimal(inv, "LegalMonetaryTotal/PayableAmount");
        if (base != null && base.subtract(sumLines).abs().compareTo(TOL) > 0) {
            out.add(new Finding("R-D2-02", DefectType.D2, "LegalMonetaryTotal/LineExtensionAmount",
                "La base (" + base + ") no es la suma de las líneas (" + sumLines + ")", p("§5.4")));
        }
        if (tax != null && tax.subtract(sumTax).abs().compareTo(TOL) > 0) {
            out.add(new Finding("R-D2-03", DefectType.D2, "TaxTotal/TaxAmount",
                "El impuesto (" + tax + ") no coincide con Σ base × tarifa (" + sumTax.setScale(2, RoundingMode.HALF_UP) + ")", p("§5.4")));
        }
        if (base != null && tax != null && pay != null && base.add(tax).subtract(pay).abs().compareTo(TOL) > 0) {
            out.add(new Finding("R-D2-02", DefectType.D2, "LegalMonetaryTotal/PayableAmount",
                "Total a pagar (" + pay + ") ≠ base + impuesto (" + base.add(tax) + ")", p("§5.4")));
        }
    }
}
