package org.veriagent.contracts.model;

import java.util.Locale;
import java.util.Set;

/**
 * Países del prototipo. CO y PE son los del documento; XX es un país sintético para el escenario de
 * incorporación (S5). Cada país fija su moneda por defecto, sus tipos de identificación y sus tributos.
 */
public enum Country {
    CO("COP", Set.of(IdType.NIT, IdType.CC, IdType.CE, IdType.PAS), Set.of(TaxCode.IVA19, TaxCode.IVA5, TaxCode.EXENTO)),
    PE("PEN", Set.of(IdType.RUC, IdType.DNI, IdType.CE, IdType.PAS), Set.of(TaxCode.IGV18, TaxCode.EXONERADO)),
    XX("USD", Set.of(IdType.TIN, IdType.PAS), Set.of(TaxCode.VAT12, TaxCode.ZERO));

    private final String defaultCurrency;
    private final Set<IdType> idTypes;
    private final Set<TaxCode> taxCodes;

    Country(String defaultCurrency, Set<IdType> idTypes, Set<TaxCode> taxCodes) {
        this.defaultCurrency = defaultCurrency;
        this.idTypes = idTypes;
        this.taxCodes = taxCodes;
    }

    public String defaultCurrency() { return defaultCurrency; }
    public Set<IdType> idTypes() { return idTypes; }
    public Set<TaxCode> taxCodes() { return taxCodes; }

    /** Código en minúsculas que se usa en los nombres de topic: co, pe, xx. */
    public String topicCode() { return name().toLowerCase(Locale.ROOT); }
}
