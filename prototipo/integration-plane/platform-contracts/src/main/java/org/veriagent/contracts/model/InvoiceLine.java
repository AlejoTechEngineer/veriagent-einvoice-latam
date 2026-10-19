package org.veriagent.contracts.model;

import java.math.BigDecimal;

/** Línea del comprobante. Invariante: cantidad × precio = valor de la línea (tolerancia 0,01). */
public record InvoiceLine(int number, String description, BigDecimal quantity, BigDecimal unitPrice,
                          BigDecimal amount, TaxCode taxCode, BigDecimal taxRate) {
}
