package org.veriagent.provider.mapping;

import org.veriagent.contracts.model.InvoiceLine;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.contracts.model.Party;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Lectura de valores del canónico y transformaciones de una entrada de mapeo. Es la misma semántica que
 * {@code tfg_agents/mapping.py} del plano agéntico, para que un mapeo propuesto por un agente produzca en Java
 * exactamente el documento que el kit evaluó en la propuesta.
 */
final class MappingValues {
    private MappingValues() {}

    /** Valor de una entrada para el comprobante (y la línea, si la fuente es {@code lines[].x}); null si no aplica. */
    static String value(JsonNode entry, InvoiceRequest inv, InvoiceLine line) {
        String transform = text(entry, "transform", "identity");
        if ("literal".equals(transform)) return text(entry, "value", null);
        String src = text(entry, "source", null);
        if (src == null) return null;
        Object raw = src.startsWith("lines[].") ? lineField(line, src.substring("lines[].".length())) : field(inv, src);
        return apply(entry, transform, raw);
    }

    static String apply(JsonNode entry, String transform, Object value) {
        if (value == null) return null;
        return switch (transform) {
            case "map" -> {
                JsonNode t = entry.get("table");
                String k = asString(value);
                yield t != null && t.has(k) ? t.get(k).asString() : "?" + k;
            }
            case "decimal2" -> decimal(value).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
            case "decimal" -> {
                BigDecimal d = decimal(value).stripTrailingZeros();
                yield d.scale() < 0 ? d.setScale(0).toPlainString() : d.toPlainString();
            }
            default -> asString(value);
        };
    }

    static Object field(InvoiceRequest inv, String path) {
        return switch (path) {
            case "correlationId" -> inv.correlationId();
            case "documentNumber" -> inv.documentNumber();
            case "documentType" -> inv.documentType();
            case "issueDate" -> inv.issueDate();
            case "currency" -> inv.currency();
            case "originalDocumentNumber" -> inv.originalDocumentNumber();
            case "country" -> inv.country();
            case "totals.baseAmount" -> inv.totals().baseAmount();
            case "totals.taxAmount" -> inv.totals().taxAmount();
            case "totals.payableAmount" -> inv.totals().payableAmount();
            default -> {
                if (path.startsWith("supplier.")) yield party(inv.supplier(), path.substring(9));
                if (path.startsWith("customer.")) yield party(inv.customer(), path.substring(9));
                yield null;
            }
        };
    }

    private static Object party(Party p, String f) {
        if (p == null) return null;
        return switch (f) {
            case "name" -> p.name();
            case "taxId" -> p.taxId();
            case "idType" -> p.idType();
            case "email" -> p.email();
            default -> null;
        };
    }

    static Object lineField(InvoiceLine l, String f) {
        if (l == null) return null;
        return switch (f) {
            case "number" -> l.number();
            case "description" -> l.description();
            case "quantity" -> l.quantity();
            case "unitPrice" -> l.unitPrice();
            case "amount" -> l.amount();
            case "taxCode" -> l.taxCode();
            case "taxRate" -> l.taxRate();
            default -> null;
        };
    }

    static String asString(Object v) {
        if (v instanceof Enum<?> e) return e.name();
        if (v instanceof BigDecimal d) return d.toPlainString();
        return String.valueOf(v);
    }

    static BigDecimal decimal(Object v) {
        return v instanceof BigDecimal d ? d : new BigDecimal(asString(v));
    }

    static String text(JsonNode n, String field, String def) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? def : v.asString();
    }
}
