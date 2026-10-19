package org.veriagent.provider.mapping;

import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.model.InvoiceLine;
import org.veriagent.contracts.model.InvoiceRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Documento JSON del proveedor Gamma (especificación proveedor-gamma-v1) a partir de un mapeo {@code gamma-json-1}.
 * Cada entrada del mapeo tiene un destino tipo puntero JSON ({@code /header/number}, {@code /lines[]/qty}); el
 * orden de las claves sigue el de la especificación. Los valores son cadenas, salvo la transformación
 * {@code integer}, que escribe un número.
 */
public class GammaJsonRenderer implements Renderer {
    static final List<String> HEADER_LAYOUT = List.of(
        "/header/specVersion", "/header/number", "/header/issueDate", "/header/documentType", "/header/currency",
        "/header/originalNumber",
        "/parties/seller/name", "/parties/seller/taxId", "/parties/seller/idScheme",
        "/parties/buyer/name", "/parties/buyer/taxId", "/parties/buyer/idScheme", "/parties/buyer/email");
    static final List<String> LINE_LAYOUT = List.of(
        "/lines[]/lineNo", "/lines[]/description", "/lines[]/qty", "/lines[]/unitPrice", "/lines[]/net",
        "/lines[]/taxCode", "/lines[]/taxRate");
    static final List<String> TOTALS_LAYOUT = List.of("/totals/net", "/totals/tax", "/totals/gross");

    @Override
    public String mappingProfile() { return "gamma-json-1"; }

    @Override
    public String kitProfile() { return "gamma"; }

    @Override
    public String render(InvoiceRequest inv, JsonNode mapping) {
        Map<String, JsonNode> byTarget = new LinkedHashMap<>();
        for (JsonNode e : mapping.get("entries")) byTarget.put(e.get("target").asString(), e);
        ObjectNode root = ContractJson.mapper().createObjectNode();
        for (String t : HEADER_LAYOUT) put(root, t, byTarget.get(t), inv, null);
        // Destinos que no están en la especificación (por ejemplo, un campo retirado) se escriben al final del encabezado.
        for (var e : byTarget.entrySet()) {
            String t = e.getKey();
            if (!HEADER_LAYOUT.contains(t) && !LINE_LAYOUT.contains(t) && !TOTALS_LAYOUT.contains(t)) put(root, t, e.getValue(), inv, null);
        }
        ArrayNode lines = root.putArray("lines");
        for (InvoiceLine l : inv.lines()) {
            ObjectNode ln = lines.addObject();
            for (String t : LINE_LAYOUT) put(ln, t.substring("/lines[]".length()), byTarget.get(t), inv, l);
        }
        for (String t : TOTALS_LAYOUT) put(root, t, byTarget.get(t), inv, null);
        return ContractJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private static void put(ObjectNode base, String pointer, JsonNode entry, InvoiceRequest inv, InvoiceLine line) {
        if (entry == null) return;
        String value = MappingValues.value(entry, inv, line);
        if (value == null) return;
        String[] parts = pointer.substring(1).split("/");
        ObjectNode cur = base;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode next = cur.get(parts[i]);
            cur = next instanceof ObjectNode o ? o : cur.putObject(parts[i]);
        }
        String leaf = parts[parts.length - 1];
        if ("integer".equals(MappingValues.text(entry, "transform", "identity"))) cur.put(leaf, Integer.parseInt(value));
        else cur.put(leaf, value);
    }
}
