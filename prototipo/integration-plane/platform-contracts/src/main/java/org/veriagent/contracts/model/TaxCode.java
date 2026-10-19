package org.veriagent.contracts.model;

import java.math.BigDecimal;

/** Tributos del modelo canónico con su tarifa. Las tarifas de CO y PE son las vigentes publicadas. */
public enum TaxCode {
    IVA19("19.00"), IVA5("5.00"), EXENTO("0.00"),
    IGV18("18.00"), EXONERADO("0.00"),
    VAT12("12.00"), ZERO("0.00");

    private final BigDecimal rate;

    TaxCode(String rate) { this.rate = new BigDecimal(rate); }

    public BigDecimal rate() { return rate; }
}
