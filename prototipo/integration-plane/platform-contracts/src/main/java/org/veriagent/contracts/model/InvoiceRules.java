package org.veriagent.contracts.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Invariantes del modelo canónico que JSON Schema no puede expresar: coherencia aritmética, tributos del país
 * y documento original de las notas. Son las mismas reglas que valida el modelo Pydantic del plano agéntico.
 */
public final class InvoiceRules {
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private InvoiceRules() {}

    /** Lista de violaciones en español; vacía si el comprobante es coherente. */
    public static List<String> violations(InvoiceRequest inv) {
        List<String> out = new ArrayList<>();
        if (inv.lines() == null || inv.lines().isEmpty()) {
            out.add("el comprobante no tiene líneas");
            return out;
        }
        BigDecimal base = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (InvoiceLine l : inv.lines()) {
            if (l.quantity().multiply(l.unitPrice()).subtract(l.amount()).abs().compareTo(TOLERANCE) > 0) {
                out.add("línea " + l.number() + ": cantidad × precio ≠ valor de línea");
            }
            if (l.taxRate().compareTo(l.taxCode().rate()) != 0) {
                out.add("línea " + l.number() + ": la tarifa " + l.taxRate() + " no corresponde a " + l.taxCode());
            }
            if (!inv.country().taxCodes().contains(l.taxCode())) {
                out.add("línea " + l.number() + ": el tributo " + l.taxCode() + " no aplica en " + inv.country());
            }
            base = base.add(l.amount());
            tax = tax.add(l.amount().multiply(l.taxRate()).divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP));
        }
        base = base.setScale(2, RoundingMode.HALF_UP);
        tax = tax.setScale(2, RoundingMode.HALF_UP);
        if (base.compareTo(inv.totals().baseAmount()) != 0) {
            out.add("la base " + inv.totals().baseAmount() + " no es la suma de las líneas " + base);
        }
        if (tax.compareTo(inv.totals().taxAmount()) != 0) {
            out.add("el impuesto " + inv.totals().taxAmount() + " no coincide con las líneas " + tax);
        }
        if (inv.totals().baseAmount().add(inv.totals().taxAmount()).compareTo(inv.totals().payableAmount()) != 0) {
            out.add("el total a pagar no es base + impuesto");
        }
        if (inv.documentType().isNote() != (inv.originalDocumentNumber() != null)) {
            out.add("solo las notas llevan documento original, y todas lo llevan");
        }
        for (Party p : List.of(inv.supplier(), inv.customer())) {
            if (!inv.country().idTypes().contains(p.idType())) {
                out.add("el tipo de identificación " + p.idType() + " no aplica en " + inv.country());
            }
        }
        return out;
    }
}
