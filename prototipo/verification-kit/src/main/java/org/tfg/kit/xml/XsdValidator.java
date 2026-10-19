package org.tfg.kit.xml;

import org.tfg.kit.core.DefectType;
import org.tfg.kit.core.Finding;
import org.tfg.kit.core.Provenance;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Validación XSD de la versión vigente. Clasifica cada error en la taxonomía cuando es posible. */
public final class XsdValidator {
    private final Schema schema;
    private final Set<String> retiredLocalNames;
    private final Provenance provenance;

    public XsdValidator(Path xsd, Set<String> retiredLocalNames, Provenance provenance) throws Exception {
        SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        sf.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        sf.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
        this.schema = sf.newSchema(xsd.toFile());
        this.retiredLocalNames = retiredLocalNames;
        this.provenance = provenance;
    }

    public List<Finding> validate(String xml) {
        List<Finding> out = new ArrayList<>();
        try {
            Validator v = schema.newValidator();
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            v.setErrorHandler(new ErrorHandler() {
                public void warning(SAXParseException e) {}
                public void error(SAXParseException e) { out.add(toFinding(e)); }
                public void fatalError(SAXParseException e) { out.add(toFinding(e)); }
            });
            v.validate(new StreamSource(new StringReader(xml)));
        } catch (Exception e) {
            if (out.isEmpty()) out.add(new Finding("XSD-00", DefectType.E0, "documento", e.getMessage(), provenance));
        }
        return out;
    }

    private Finding toFinding(SAXParseException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        DefectType t = DefectType.E0;
        for (String retired : retiredLocalNames) {
            if (m.contains("'" + retired + "'") || m.contains(":" + retired + "'")) { t = DefectType.D5; break; }
        }
        if (t == DefectType.E0 && (m.contains("is not complete") || m.contains("One of '{") && m.contains("is expected"))) {
            t = DefectType.D4;
        }
        return new Finding("XSD-01", t, "línea " + e.getLineNumber(), m, provenance);
    }
}
