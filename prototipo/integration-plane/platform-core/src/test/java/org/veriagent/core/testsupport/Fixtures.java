package org.veriagent.core.testsupport;

import org.veriagent.contracts.model.Country;
import org.veriagent.contracts.model.DocumentType;
import org.veriagent.contracts.model.IdType;
import org.veriagent.contracts.model.InvoiceLine;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.contracts.model.Party;
import org.veriagent.contracts.model.TaxCode;
import org.veriagent.contracts.model.Totals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Comprobantes canónicos válidos para las pruebas de componente. */
public final class Fixtures {
    private Fixtures() {}

    /** Factura de CO válida para el Perfil Alfa v2.1 (NIT de 9 dígitos con verificación, CC de 6 a 10 dígitos). */
    public static InvoiceRequest coInvoice(String number) {
        var l1 = new InvoiceLine(1, "Servicio de soporte", new BigDecimal("2"), new BigDecimal("100.00"),
            new BigDecimal("200.00"), TaxCode.IVA19, new BigDecimal("19.00"));
        var l2 = new InvoiceLine(2, "Capacitación virtual", new BigDecimal("1"), new BigDecimal("50.00"),
            new BigDecimal("50.00"), TaxCode.IVA5, new BigDecimal("5.00"));
        return new InvoiceRequest(Country.CO, "corr-" + UUID.randomUUID(), number, DocumentType.INVOICE,
            LocalDate.of(2026, 10, 1), "COP", null,
            new Party("Comercializadora Sintética S.A.S.", "900123456-8", IdType.NIT, null),
            new Party("Cliente de Prueba", "1020304050", IdType.CC, "cliente@ejemplo.test"),
            List.of(l1, l2), new Totals(new BigDecimal("250.00"), new BigDecimal("40.50"), new BigDecimal("290.50")));
    }

    public static String number() {
        return "FE-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
