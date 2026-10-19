package org.veriagent.provider.mapping;

import org.veriagent.contracts.model.InvoiceLine;
import org.veriagent.contracts.model.InvoiceRequest;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Formato por segmentos del proveedor Beta (§B de la especificación): ENC, DET y TOT separados por "|".
 * El separador es reservado: los textos libres se sanean reemplazándolo por "/" si el mapeo lo indica.
 */
public class BetaSegmentRenderer implements Renderer {
    private final String mappingProfile;
    private final String kitProfile;

    public BetaSegmentRenderer() {
        this("beta-segmentos", "beta");
    }

    /** Variantes del mismo formato con otros catálogos (por ejemplo, el perfil de Perú). */
    public BetaSegmentRenderer(String mappingProfile, String kitProfile) {
        this.mappingProfile = mappingProfile;
        this.kitProfile = kitProfile;
    }

    @Override
    public String mappingProfile() { return mappingProfile; }

    @Override
    public String kitProfile() { return kitProfile; }

    @Override
    public String render(InvoiceRequest inv, JsonNode cfg) {
        List<String> out = new ArrayList<>();
        out.add(String.join("|", "ENC", inv.documentNumber(), inv.issueDate().toString(),
            table(cfg, "docTypeTable", inv.documentType().name()), inv.currency(),
            inv.supplier().taxId(), inv.customer().taxId(), text(inv.customer().name(), cfg, "sanitizeCustomerName")));
        for (InvoiceLine l : inv.lines()) {
            BigDecimal q = l.quantity().stripTrailingZeros();
            out.add(String.join("|", "DET", String.valueOf(l.number()), text(l.description(), cfg, "sanitizeDescription"),
                q.scale() < 0 ? q.setScale(0).toPlainString() : q.toPlainString(), two(l.unitPrice()), two(l.amount()),
                table(cfg, "taxTable", l.taxCode().name())));
        }
        out.add(String.join("|", "TOT", two(inv.totals().baseAmount()), two(inv.totals().taxAmount()),
            two(inv.totals().payableAmount())));
        return String.join("\n", out) + "\n";
    }

    private static String table(JsonNode cfg, String name, String key) {
        JsonNode t = cfg.get(name);
        return t != null && t.has(key) ? t.get(key).asString() : "?" + key;
    }

    private static String text(String v, JsonNode cfg, String flag) {
        boolean sanitize = !cfg.has(flag) || cfg.get(flag).asBoolean();
        return sanitize ? v.replace("|", "/") : v;
    }

    private static String two(BigDecimal d) {
        return d.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }
}
