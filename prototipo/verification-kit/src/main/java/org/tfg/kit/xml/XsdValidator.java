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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validación XSD de la versión vigente. Clasifica cada error en la taxonomía cuando es posible.
 *
 * <p>La clasificación usa el código de error de XML Schema ({@code cvc-...}), que JAXP antepone al mensaje
 * en cualquier idioma, y el nombre del elemento. Nunca depende del texto traducido del mensaje.
 */
public final class XsdValidator {
    /** Código de error de XML Schema al inicio del mensaje, por ejemplo {@code cvc-complex-type.2.4.b}. */
    private static final Pattern CVC_CODE = Pattern.compile("^\\s*(cvc-[A-Za-z0-9.-]+?)\\s*:");
    /** Contenido incompleto (2.4.b) o un elemento donde se esperaba otro obligatorio (2.4.a). */
    private static final Set<String> MISSING_CONTENT = Set.of("cvc-complex-type.2.4.a", "cvc-complex-type.2.4.b");

    private final Schema schema;
    private final Set<String> retiredLocalNames;
    private final Provenance provenance;

    public XsdValidator(Path xsd, Set<String> retiredLocalNames, Provenance provenance) throws Exception {
        SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        sf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
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
            v.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
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

    /** Código {@code cvc-...} del mensaje, o cadena vacía si no lo trae. */
    static String errorCode(String message) {
        Matcher m = CVC_CODE.matcher(message == null ? "" : message);
        return m.find() ? m.group(1) : "";
    }

    /** Tipo de defecto de un error del XSD, independiente del idioma del mensaje. */
    static DefectType classify(String message, Set<String> retiredLocalNames) {
        String m = message == null ? "" : message;
        for (String retired : retiredLocalNames) {
            if (Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(retired) + "(?![A-Za-z0-9_])").matcher(m).find()) {
                return DefectType.D5;
            }
        }
        return MISSING_CONTENT.contains(errorCode(m)) ? DefectType.D4 : DefectType.E0;
    }

    private Finding toFinding(SAXParseException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        return new Finding("XSD-01", classify(m, retiredLocalNames), "línea " + e.getLineNumber(), m, provenance);
    }
}
