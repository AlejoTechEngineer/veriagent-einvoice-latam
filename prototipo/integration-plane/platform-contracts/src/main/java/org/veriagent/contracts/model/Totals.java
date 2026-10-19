package org.veriagent.contracts.model;

import java.math.BigDecimal;

/** Totales del comprobante: base, impuesto y total a pagar. */
public record Totals(BigDecimal baseAmount, BigDecimal taxAmount, BigDecimal payableAmount) {
}
