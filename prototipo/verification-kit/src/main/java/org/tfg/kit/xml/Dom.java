package org.tfg.kit.xml;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Utilidades DOM con espacios de nombres y protecciones contra XXE. */
public final class Dom {
    public static final String NS = "urn:tfg:alfa:invoice:2";
    private Dom() {}

    public static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    /** Hijos directos con un nombre local dado. */
    public static List<Element> children(Element parent, String local) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && local.equals(e.getLocalName())) out.add(e);
        }
        return out;
    }

    /** Resuelve una ruta relativa "A/B/C" o "A/B/@attr" desde un elemento. Devuelve null si no existe. */
    public static String value(Element from, String path) {
        String[] parts = path.split("/");
        Element cur = from;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.startsWith("@")) return cur.hasAttribute(p.substring(1)) ? cur.getAttribute(p.substring(1)) : null;
            List<Element> c = children(cur, p);
            if (c.isEmpty()) return null;
            cur = c.get(0);
        }
        return cur.getTextContent();
    }

    public static BigDecimal decimal(Element from, String path) {
        String v = value(from, path);
        if (v == null || v.isBlank()) return null;
        try { return new BigDecimal(v.trim()); } catch (NumberFormatException e) { return null; }
    }
}
