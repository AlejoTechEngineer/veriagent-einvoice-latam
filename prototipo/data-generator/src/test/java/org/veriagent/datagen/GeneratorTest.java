package org.veriagent.datagen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.ContractValidator;
import org.veriagent.contracts.model.Country;
import org.veriagent.contracts.model.IdType;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.contracts.model.InvoiceRules;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

class GeneratorTest {
    static final Path HOME = Path.of(System.getProperty("veriagent.home", "..")).toAbsolutePath().normalize();
    static final Numbering NUMBERING = Numbering.load(HOME.resolve("data/numeracion"));
    final ContractValidator validator = new ContractValidator();

    @Test
    @DisplayName("Dígitos de control: NIT (DIAN), RUC (SUNAT) y Luhn, con valores de referencia públicos")
    void checkDigits() {
        assertEquals(8, TaxIds.nitCheckDigit("900123456"));
        assertTrue(TaxIds.validNit("900123456-8"));
        assertFalse(TaxIds.validNit("900123456-7"));
        assertEquals(6, TaxIds.rucCheckDigit("2012345678"));
        assertTrue(TaxIds.validRuc("20123456786"));
        assertEquals(3, TaxIds.luhnCheckDigit("7992739871"));
        assertTrue(TaxIds.validTin("1234567897"));
        SplittableRandom r = new SplittableRandom(1);
        for (int i = 0; i < 500; i++) {
            assertTrue(TaxIds.validNit(TaxIds.nit(r)));
            assertTrue(TaxIds.validRuc(TaxIds.ruc(r)));
            assertTrue(TaxIds.validTin(TaxIds.tin(r)));
        }
    }

    @Test
    @DisplayName("Cada comprobante generado cumple el esquema canónico y sus invariantes, en los tres países")
    void generatedInvoicesAreValid() {
        for (Country c : Country.values()) {
            var g = new InvoiceGenerator(NUMBERING, 2026, 0);
            for (InvoiceRequest inv : g.generate(c, 300)) {
                assertEquals(List.of(), validator.validate(ContractValidator.CANONICAL, ContractJson.mapper().valueToTree(inv)), inv.documentNumber());
                assertEquals(List.of(), InvoiceRules.violations(inv), inv.documentNumber());
                if (inv.customer().idType() == IdType.NIT) assertTrue(TaxIds.validNit(inv.customer().taxId()));
                if (inv.customer().idType() == IdType.RUC) assertTrue(TaxIds.validRuc(inv.customer().taxId()));
                if (inv.customer().idType() == IdType.TIN) assertTrue(TaxIds.validTin(inv.customer().taxId()));
            }
        }
    }

    @Test
    @DisplayName("Reproducible con la misma semilla; numeración del CSV, sin repetir dentro de un lote")
    void reproducibleAndNumbered() {
        var a = new InvoiceGenerator(NUMBERING, 99, 0).generate(Country.PE, 50);
        var b = new InvoiceGenerator(NUMBERING, 99, 0).generate(Country.PE, 50);
        assertEquals(a, b);
        assertEquals("F001-00000001", a.get(0).documentNumber());
        Set<String> keys = new HashSet<>();
        a.forEach(i -> assertTrue(keys.add(i.invoiceKey())));
        assertEquals("SETP990000001", new InvoiceGenerator(NUMBERING, 1, 0).generate(Country.CO, 1).get(0).documentNumber());
        assertEquals("SETP990000101", new InvoiceGenerator(NUMBERING, 1, 100).generate(Country.CO, 1).get(0).documentNumber());
    }

    @Test
    @DisplayName("Ejemplos curados válidos y defectos de datos con el efecto esperado en el contrato")
    void fixturesAndDefects() {
        for (Country c : Country.values()) {
            for (InvoiceRequest inv : InvoiceGenerator.fixtures(NUMBERING, c)) assertEquals(List.of(), InvoiceRules.violations(inv));
        }
        var g = new InvoiceGenerator(NUMBERING, 5, 0);
        assertTrue(validator.validate(ContractValidator.CANONICAL, g.defective(InvoiceGenerator.DataDefect.D1_ID_FORMAT)).isEmpty(), "D1 pasa el esquema; lo detecta el kit");
        assertTrue(validator.validate(ContractValidator.CANONICAL, g.defective(InvoiceGenerator.DataDefect.D4_MISSING_EMAIL)).isEmpty(), "D4 pasa el esquema; lo detecta el kit");
        assertFalse(validator.validate(ContractValidator.CANONICAL, g.defective(InvoiceGenerator.DataDefect.D3_CURRENCY_CASE)).isEmpty(), "D3 lo rechaza el esquema");
        var d2 = ContractJson.mapper().treeToValue(g.defective(InvoiceGenerator.DataDefect.D2_LINE_AMOUNT), InvoiceRequest.class);
        assertFalse(InvoiceRules.violations(d2).isEmpty(), "D2 lo rechazan las invariantes");
    }
}
