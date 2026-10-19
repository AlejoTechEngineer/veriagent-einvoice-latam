package org.tfg.kit.xml;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tfg.kit.core.DefectType;
import org.tfg.kit.core.Finding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La clasificación de los errores del XSD no puede depender del idioma de los mensajes de JAXP.
 * Estas pruebas fijan la configuración regional dentro de la propia prueba; además, la suite completa
 * se ejecuta dos veces (es y en) desde el POM.
 */
class XsdLocaleTest {
    private static final Set<String> RETIRED = Set.of("LegacyOrderReference", "TaxExchangeRateLegacy");
    private final Locale original = Locale.getDefault();

    @AfterEach
    void restore() { Locale.setDefault(original); }

    private static XsdValidator validator() throws Exception {
        Path home = Path.of(System.getProperty("kit.home", "..")).toAbsolutePath();
        return new XsdValidator(home.resolve("schemas/perfil-alfa-v2.1.xsd"), RETIRED, null);
    }

    private static String valid() throws Exception {
        return Files.readString(Path.of("src/test/resources/factura-valida.xml"));
    }

    @ParameterizedTest(name = "configuración regional {0}")
    @ValueSource(strings = {"es-CO", "en-US"})
    @DisplayName("D4: el correo ausente se clasifica igual con mensajes en español y en inglés")
    void missingMailIsD4InAnyLocale(String tag) throws Exception {
        Locale.setDefault(Locale.forLanguageTag(tag));
        String missing = valid().replace("<ElectronicMail>cliente@ejemplo.test</ElectronicMail>", "");
        List<Finding> fs = validator().validate(missing);
        assertFalse(fs.isEmpty(), "el XSD debe rechazar el documento");
        assertTrue(fs.stream().anyMatch(f -> f.type() == DefectType.D4), () -> tag + " -> " + fs);
    }

    @ParameterizedTest(name = "configuración regional {0}")
    @ValueSource(strings = {"es-CO", "en-US"})
    @DisplayName("D5: un elemento retirado se clasifica igual con mensajes en español y en inglés")
    void retiredElementIsD5InAnyLocale(String tag) throws Exception {
        Locale.setDefault(Locale.forLanguageTag(tag));
        String retired = valid().replace("</IssueDate>", "</IssueDate><LegacyOrderReference>X</LegacyOrderReference>");
        List<Finding> fs = validator().validate(retired);
        assertTrue(fs.stream().anyMatch(f -> f.type() == DefectType.D5), () -> tag + " -> " + fs);
    }

    @Test
    @DisplayName("La clasificación usa el código cvc-..., no el texto traducido")
    void classifiesByCodeNotByText() {
        String es = "cvc-complex-type.2.4.b: El contenido del elemento 'Party' no está completo.";
        String en = "cvc-complex-type.2.4.b: The content of element 'Party' is not complete.";
        assertEquals("cvc-complex-type.2.4.b", XsdValidator.errorCode(es));
        assertEquals(DefectType.D4, XsdValidator.classify(es, RETIRED));
        assertEquals(DefectType.D4, XsdValidator.classify(en, RETIRED));
        assertEquals(DefectType.E0, XsdValidator.classify("cvc-datatype-valid.1.2.1: '2026-13-40' no es válido.", RETIRED));
        assertEquals(DefectType.E0, XsdValidator.classify("mensaje sin código", RETIRED));
        // Un nombre que solo contiene al retirado como subcadena no es D5.
        assertEquals(DefectType.E0, XsdValidator.classify("cvc-x: 'LegacyOrderReferenceV3'", RETIRED));
    }
}
