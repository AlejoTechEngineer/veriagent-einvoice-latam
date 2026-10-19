package org.tfg.kit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.tfg.kit.core.DefectType;
import org.tfg.kit.core.Finding;
import org.tfg.kit.core.Mode;
import org.tfg.kit.core.VerificationReport;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VerificationKitTest {
    static VerificationKit kit;
    static String valid;
    static String betaValid;

    @BeforeAll
    static void setUp() throws Exception {
        Path home = Path.of(System.getProperty("kit.home", "..")).toAbsolutePath();
        kit = new VerificationKit(home);
        valid = Files.readString(Path.of("src/test/resources/factura-valida.xml"));
        betaValid = Files.readString(Path.of("src/test/resources/beta-valido.txt"));
    }

    private static boolean has(VerificationReport r, DefectType t) {
        return r.findings().stream().anyMatch(f -> f.type() == t);
    }

    @Test @DisplayName("Un comprobante correcto pasa en los tres modos (sin falsos positivos)")
    void validPassesAllModes() {
        for (Mode m : Mode.values()) {
            VerificationReport r = kit.verifyXml("ok", valid, m, valid);
            assertTrue(r.passed(), () -> m + " -> " + r.toJson());
        }
    }

    @Test @DisplayName("Cada hallazgo de regla cita su sección y el hash del documento normativo")
    void findingsCarryProvenance() {
        VerificationReport r = kit.verifyXml("p", valid.replace("<InvoiceTypeCode>01<", "<InvoiceTypeCode>07<"), Mode.RUNTIME, null);
        Finding f = r.findings().stream().filter(x -> x.ruleId().equals("R-D1-01")).findFirst().orElseThrow();
        assertEquals("§3.2", f.provenance().section());
        assertEquals(kit.spec().sha256(), f.provenance().sourceSha256());
        assertEquals(64, f.provenance().sourceSha256().length());
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = ';', value = {
        "D1;tipo inexistente;<InvoiceTypeCode>01<;<InvoiceTypeCode>07<",
        "D1;nota crédito sin referencia;<InvoiceTypeCode>01<;<InvoiceTypeCode>91<",
        "D1;identificación con tipo equivocado;schemeName=\"A2\">10203040;schemeName=\"A1\">10203040",
        "D1;tributo con tarifa de otro código;<ID>T01</ID><Percent>19.00;<ID>T02</ID><Percent>19.00",
        "D2;precio y valor de línea intercambiados;<LineExtensionAmount>200.00</LineExtensionAmount><ItemDescription>Servicio de soporte</ItemDescription><PriceAmount>100.00;<LineExtensionAmount>100.00</LineExtensionAmount><ItemDescription>Servicio de soporte</ItemDescription><PriceAmount>200.00",
        "D2;total a pagar tomado de la base;<PayableAmount>357.00<;<PayableAmount>300.00<",
        "D3;perfil en minúsculas;PerfilAlfa-2.1;perfilalfa-2.1",
        "D3;esquema con espacio final;<TaxSchemeName>IVA</TaxSchemeName>;<TaxSchemeName>IVA </TaxSchemeName>",
        "D3;moneda en minúsculas;<DocumentCurrencyCode>COP<;<DocumentCurrencyCode>cop<",
        "D4;correo vacío;<ElectronicMail>cliente@ejemplo.test</ElectronicMail>;<ElectronicMail></ElectronicMail>",
        "D5;elemento retirado;<ID>FA-000001</ID>;<ID>FA-000001</ID><LegacyOrderReference>OC-9</LegacyOrderReference>"
    })
    void runtimeDetectsDefect(String type, String name, String from, String to) {
        String mutated = valid.replace(from, to);
        assertNotEquals(valid, mutated, "la mutación no se aplicó: " + name);
        VerificationReport r = kit.verifyXml(name, mutated, Mode.RUNTIME, null);
        assertTrue(has(r, DefectType.valueOf(type)), () -> name + " -> " + r.toJson());
    }

    @Test @DisplayName("D4: el XSD detecta el correo ausente, pero no el correo vacío; la regla sí")
    void emptyMailEscapesSchemaButNotRules() {
        String empty = valid.replace("cliente@ejemplo.test", "");
        assertTrue(kit.verifyXml("e", empty, Mode.SCHEMA_ONLY, null).passed());
        assertTrue(has(kit.verifyXml("e", empty, Mode.RUNTIME, null), DefectType.D4));
        String missing = valid.replace("<ElectronicMail>cliente@ejemplo.test</ElectronicMail>", "");
        assertTrue(has(kit.verifyXml("m", missing, Mode.SCHEMA_ONLY, null), DefectType.D4));
    }

    @Test @DisplayName("D2: cantidad y precio intercambiados escapan a las invariantes; el fixture de CI lo detecta")
    void swappedQuantityAndPriceNeedsFixture() {
        String swapped = valid.replace("<InvoicedQuantity>2</InvoicedQuantity>", "<InvoicedQuantity>100.00</InvoicedQuantity>")
                              .replace("<ItemDescription>Servicio de soporte</ItemDescription><PriceAmount>100.00", "<ItemDescription>Servicio de soporte</ItemDescription><PriceAmount>2.00");
        assertTrue(kit.verifyXml("s", swapped, Mode.RUNTIME, null).passed(), "las invariantes no pueden verlo");
        assertFalse(kit.verifyXml("s", swapped, Mode.CI, valid).passed(), "el fixture sí");
    }

    @Test @DisplayName("Beta: el formato válido pasa y un separador sin sanear se detecta como D6")
    void betaSegments() {
        assertTrue(kit.verifySegments("b", betaValid, Mode.CI, betaValid).passed());
        String unsanitized = betaValid.replace("Servicio de soporte", "Servicio | soporte");
        assertTrue(has(kit.verifySegments("b", unsanitized, Mode.SCHEMA_ONLY, null), DefectType.D6));
    }

    @Test @DisplayName("Seguridad: un DOCTYPE (XXE) se rechaza")
    void rejectsDoctype() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" + valid.substring(valid.indexOf("<Invoice"));
        assertFalse(kit.verifyXml("x", xxe, Mode.RUNTIME, null).passed());
    }

    @Test @DisplayName("Seguridad: el DOCTYPE se rechaza antes de cualquier análisis, también en modo solo esquema")
    void rejectsDoctypeBeforeSchemaValidation() {
        String bomb = "<?xml version=\"1.0\"?><!doctype x [<!ENTITY a \"aaaa\"><!ENTITY b \"&a;&a;&a;&a;\">]>"
            + valid.substring(valid.indexOf("<Invoice"));
        VerificationReport r = kit.verifyXml("b", bomb, Mode.SCHEMA_ONLY, null);
        assertEquals(1, r.findings().size(), r::toJson);
        assertEquals("R-E0-03", r.findings().get(0).ruleId());
    }
}
