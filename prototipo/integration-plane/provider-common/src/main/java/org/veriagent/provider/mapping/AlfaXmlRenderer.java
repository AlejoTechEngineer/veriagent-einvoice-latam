package org.veriagent.provider.mapping;

import org.veriagent.contracts.model.InvoiceLine;
import org.veriagent.contracts.model.InvoiceRequest;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import tools.jackson.databind.JsonNode;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** XML del Perfil Alfa v2.1 (proveedor Alfa) en el orden del XSD, a partir de un mapeo {@code alfa-2.1}. */
public class AlfaXmlRenderer implements Renderer {
    public static final String NS = "urn:tfg:alfa:invoice:2";

    static final List<String> HEADER_LAYOUT = List.of(
        "Invoice/ProfileID", "Invoice/ID", "Invoice/IssueDate", "Invoice/InvoiceTypeCode",
        "Invoice/DocumentCurrencyCode", "Invoice/BillingReference",
        "Invoice/AccountingSupplierParty/Party/PartyName", "Invoice/AccountingSupplierParty/Party/CompanyID",
        "Invoice/AccountingSupplierParty/Party/CompanyID/@schemeName",
        "Invoice/AccountingCustomerParty/Party/PartyName", "Invoice/AccountingCustomerParty/Party/CompanyID",
        "Invoice/AccountingCustomerParty/Party/CompanyID/@schemeName",
        "Invoice/AccountingCustomerParty/Party/ElectronicMail",
        "Invoice/TaxTotal/TaxAmount", "Invoice/LegalMonetaryTotal/LineExtensionAmount",
        "Invoice/LegalMonetaryTotal/PayableAmount");
    static final List<String> LINE_LAYOUT = List.of(
        "InvoiceLine/ID", "InvoiceLine/InvoicedQuantity", "InvoiceLine/LineExtensionAmount",
        "InvoiceLine/ItemDescription", "InvoiceLine/PriceAmount", "InvoiceLine/TaxCategory/ID",
        "InvoiceLine/TaxCategory/Percent", "InvoiceLine/TaxCategory/TaxSchemeName");

    @Override
    public String mappingProfile() { return "alfa-2.1"; }

    @Override
    public String kitProfile() { return "alfa"; }

    @Override
    public String render(InvoiceRequest inv, JsonNode mapping) {
        Map<String, JsonNode> byTarget = new LinkedHashMap<>();
        List<JsonNode> extras = new ArrayList<>();
        for (JsonNode e : mapping.get("entries")) {
            String target = e.get("target").asString();
            byTarget.put(target, e);
            if (!HEADER_LAYOUT.contains(target) && !LINE_LAYOUT.contains(target)) extras.add(e);
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Document doc = f.newDocumentBuilder().newDocument();
            Element root = doc.createElementNS(NS, "Invoice");
            doc.appendChild(root);
            for (String target : HEADER_LAYOUT) {
                JsonNode e = byTarget.get(target);
                if (e != null) set(doc, root, target.substring("Invoice/".length()), MappingValues.value(e, inv, null));
                for (JsonNode x : extras) {
                    if (target.equals(MappingValues.text(x, "after", null))) {
                        String t = x.get("target").asString();
                        set(doc, root, t.startsWith("Invoice/") ? t.substring("Invoice/".length()) : t, MappingValues.value(x, inv, null));
                    }
                }
            }
            for (InvoiceLine line : inv.lines()) {
                Element il = doc.createElementNS(NS, "InvoiceLine");
                root.appendChild(il);
                for (String target : LINE_LAYOUT) {
                    JsonNode e = byTarget.get(target);
                    if (e != null) set(doc, il, target.substring("InvoiceLine/".length()), MappingValues.value(e, inv, line));
                }
            }
            return serialize(doc);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo construir el XML del perfil Alfa: " + e.getMessage(), e);
        }
    }

    /** Igual que {@code _set} de Python: reutiliza los intermedios, crea siempre el último y fija atributos. */
    private static void set(Document doc, Element base, String rel, String value) {
        if (value == null) return;
        String[] parts = rel.split("/");
        Element cur = base;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.startsWith("@")) {
                cur.setAttribute(p.substring(1), value);
                return;
            }
            boolean last = i == parts.length - 1;
            Element found = last ? null : child(cur, p);
            if (found == null) {
                found = doc.createElementNS(NS, p);
                cur.appendChild(found);
            }
            cur = found;
        }
        cur.setTextContent(value);
    }

    private static Element child(Element parent, String local) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && local.equals(e.getLocalName())) return e;
        }
        return null;
    }

    private static String serialize(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        t.setOutputProperty(OutputKeys.INDENT, "no");
        StringWriter w = new StringWriter();
        t.transform(new DOMSource(doc), new StreamResult(w));
        return w.toString();
    }
}
