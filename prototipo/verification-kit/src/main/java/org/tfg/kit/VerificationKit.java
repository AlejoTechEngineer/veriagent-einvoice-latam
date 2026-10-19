package org.tfg.kit;

import org.tfg.kit.catalog.CatalogRegistry;
import org.tfg.kit.core.*;
import org.tfg.kit.segment.BetaVerifier;
import org.tfg.kit.xml.AlfaRules;
import org.tfg.kit.xml.Dom;
import org.tfg.kit.xml.XsdValidator;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Fachada del kit de verificación determinista: un punto de entrada para ejecución, CI y servidores MCP. */
public final class VerificationKit {
    private final XsdValidator xsd;
    private final AlfaRules alfa;
    private final BetaVerifier beta;
    private final SpecDocument spec;

    public VerificationKit(Path home) throws Exception {
        this.spec = SpecDocument.load(home.resolve("specs/perfil-alfa-v2.1.md"));
        CatalogRegistry catalogs = CatalogRegistry.load(home.resolve("catalogs/catalogos-alfa-v2.csv"));
        Set<String> retired = catalogs.get("Retirado").entries().keySet().stream()
            .map(c -> c.substring(c.lastIndexOf('/') + 1)).collect(Collectors.toSet());
        this.xsd = new XsdValidator(home.resolve("schemas/perfil-alfa-v2.1.xsd"), retired, Provenance.of(spec, "§6 (XSD)", "2.1.0"));
        this.alfa = new AlfaRules(catalogs, spec);
        this.beta = new BetaVerifier(catalogs, spec);
    }

    public SpecDocument spec() { return spec; }

    public VerificationReport verifyXml(String id, String xml, Mode mode, String expectedXml) {
        VerificationReport r = new VerificationReport(id, mode.name());
        r.addAll(xsd.validate(xml));
        if (mode == Mode.SCHEMA_ONLY) return r;
        try {
            r.addAll(alfa.check(Dom.parse(xml)));
            if (mode == Mode.CI && expectedXml != null) {
                r.addAll(FixtureComparator.compare(leaves(Dom.parse(expectedXml).getDocumentElement(), ""),
                    leaves(Dom.parse(xml).getDocumentElement(), ""), Provenance.of(spec, "fixture validado", "2.1.0")));
            }
        } catch (Exception e) {
            r.add(new Finding("R-E0-02", DefectType.E0, "documento", "No se pudo analizar: " + e.getMessage(), null));
        }
        return r;
    }

    public VerificationReport verifySegments(String id, String text, Mode mode, String expectedText) {
        VerificationReport r = new VerificationReport(id, mode.name());
        List<Finding> s = beta.structure(text);
        r.addAll(s);
        if (mode == Mode.SCHEMA_ONLY) return r;
        r.addAll(beta.rules(text));
        if (mode == Mode.CI && expectedText != null) {
            r.addAll(FixtureComparator.compare(List.of(expectedText.strip().split("\\R")),
                List.of(text.strip().split("\\R")), Provenance.of(spec, "fixture validado", "2.1.0")));
        }
        return r;
    }

    /** Lista ordenada "ruta=valor" de hojas y atributos, para comparar con el fixture. */
    static List<String> leaves(Element e, String prefix) {
        List<String> out = new ArrayList<>();
        String path = prefix.isEmpty() ? e.getLocalName() : prefix + "/" + e.getLocalName();
        for (int i = 0; i < e.getAttributes().getLength(); i++) {
            Node a = e.getAttributes().item(i);
            if (!a.getNodeName().startsWith("xmlns")) out.add(path + "/@" + a.getNodeName() + "=" + a.getNodeValue());
        }
        boolean hasChild = false;
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c) { hasChild = true; out.addAll(leaves(c, path)); }
        }
        if (!hasChild) out.add(path + "=" + e.getTextContent());
        return out;
    }
}
