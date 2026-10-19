package org.veriagent.datagen;

import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.model.Country;
import org.veriagent.contracts.model.DocumentType;
import org.veriagent.contracts.model.IdType;
import org.veriagent.contracts.model.InvoiceLine;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.contracts.model.Party;
import org.veriagent.contracts.model.TaxCode;
import org.veriagent.contracts.model.Totals;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Generador reproducible de comprobantes canónicos sintéticos. La misma semilla produce siempre los mismos
 * comprobantes (numeración, identificadores con dígito de control válido, importes coherentes), lo que permite
 * repetir escenarios y comparar resultados.
 */
public final class InvoiceGenerator {
    private static final List<String> ITEMS = List.of("Servicio de soporte técnico", "Licencia anual de software",
        "Consultoría de integración", "Mantenimiento preventivo", "Capacitación virtual", "Hosting mensual",
        "Póliza de servicio", "Desarrollo a la medida", "Auditoría de seguridad", "Migración de datos");
    private static final List<String> NAMES = List.of("Comercial Andina S.A.S.", "Tecnologías del Valle", "Grupo Horizonte",
        "Distribuidora Central", "Servicios Integrales del Norte", "Laboratorio Mirador", "Inversiones Cordillera",
        "Ana María Rojas", "Carlos Pérez", "Logística Pacífico", "Agrícola La Pradera", "Clínica San Rafael");
    /** Emisores sintéticos fijos por país (identificador con dígito de control válido). */
    private static final Map<Country, Party> SUPPLIERS = Map.of(
        Country.CO, new Party("Comercializadora Sintética S.A.S.", "900123456-8", IdType.NIT, null),
        Country.PE, new Party("Servicios Sintéticos del Pacífico S.A.C.", "20123456786", IdType.RUC, null),
        Country.XX, new Party("Synthetic Trading Co.", "1234567897", IdType.TIN, null));

    private final Numbering numbering;
    private final SplittableRandom rng;
    private final long seqOffset;
    private long seq;

    /**
     * @param seed      semilla del generador
     * @param seqOffset posición inicial dentro del rango de numeración (para no repetir números entre lotes)
     */
    public InvoiceGenerator(Numbering numbering, long seed, long seqOffset) {
        this.numbering = numbering;
        this.rng = new SplittableRandom(seed);
        this.seqOffset = seqOffset;
    }

    /** Opciones para forzar el contenido de un comprobante (null = al azar). */
    public record Spec(DocumentType docType, IdType customerIdType, List<TaxCode> taxCodes, boolean adversarialText,
                       boolean adversarialName) {
        public static Spec random() { return new Spec(null, null, null, false, false); }
    }

    public List<InvoiceRequest> generate(Country country, int n) {
        List<InvoiceRequest> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) out.add(next(country, Spec.random()));
        return out;
    }

    public InvoiceRequest next(Country country, Spec spec) {
        long position = seqOffset + seq++;
        String number = numbering.range(country).number(position);
        DocumentType dt = spec.docType() != null ? spec.docType() : pick(DocumentType.INVOICE, 70, DocumentType.CREDIT_NOTE, 20, DocumentType.DEBIT_NOTE, 10);
        IdType it = spec.customerIdType() != null ? spec.customerIdType() : customerIdType(country);
        List<TaxCode> codes = spec.taxCodes();
        int nLines = codes != null ? codes.size() : 1 + rng.nextInt(4);
        List<InvoiceLine> lines = new ArrayList<>();
        BigDecimal base = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (int k = 0; k < nLines; k++) {
            TaxCode code = codes != null ? codes.get(k) : taxCode(country);
            BigDecimal qty = BigDecimal.valueOf(1 + rng.nextInt(12));
            BigDecimal price = BigDecimal.valueOf(1000 + rng.nextInt(499_001)).movePointLeft(2);
            BigDecimal amount = qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
            String desc = ITEMS.get(rng.nextInt(ITEMS.size()));
            if (spec.adversarialText() || rng.nextInt(20) == 0) desc = desc.replaceFirst(" ", " | ");
            lines.add(new InvoiceLine(k + 1, desc, qty, price, amount, code, code.rate()));
            base = base.add(amount);
            tax = tax.add(amount.multiply(code.rate()).divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        }
        base = base.setScale(2, RoundingMode.HALF_UP);
        tax = tax.setScale(2, RoundingMode.HALF_UP);
        String name = NAMES.get(rng.nextInt(NAMES.size()));
        if (spec.adversarialName()) name = name.replaceFirst(" ", " | ");
        Party customer = new Party(name, customerId(it), it, "cliente" + position + "@ejemplo.test");
        String original = dt == DocumentType.INVOICE ? null : numbering.range(country).number(Math.max(0, position - 1 - rng.nextInt(50)));
        return new InvoiceRequest(country, "corr-" + country.topicCode() + "-" + number, number, dt,
            LocalDate.of(2026, 9, 1).plusDays(rng.nextInt(45)), country.defaultCurrency(), original,
            SUPPLIERS.get(country), customer, lines, new Totals(base, tax, base.add(tax)));
    }

    /** Ejemplos curados por país: cada tipo de documento, cada tipo de identificación, cada tributo y datos adversarios. */
    public static List<InvoiceRequest> fixtures(Numbering numbering, Country country) {
        InvoiceGenerator g = new InvoiceGenerator(numbering, 7, 900_000);
        List<Spec> specs = switch (country) {
            case CO -> List.of(
                new Spec(DocumentType.INVOICE, IdType.NIT, List.of(TaxCode.IVA19, TaxCode.IVA5), false, false),
                new Spec(DocumentType.INVOICE, IdType.CC, List.of(TaxCode.EXENTO), false, false),
                new Spec(DocumentType.INVOICE, IdType.CE, List.of(TaxCode.IVA19), false, false),
                new Spec(DocumentType.INVOICE, IdType.PAS, List.of(TaxCode.IVA5, TaxCode.IVA19), false, false),
                new Spec(DocumentType.CREDIT_NOTE, IdType.NIT, List.of(TaxCode.IVA19), false, false),
                new Spec(DocumentType.DEBIT_NOTE, IdType.CC, List.of(TaxCode.IVA5), false, false),
                new Spec(DocumentType.INVOICE, IdType.NIT, List.of(TaxCode.IVA19), true, false),
                new Spec(DocumentType.INVOICE, IdType.CC, List.of(TaxCode.IVA19), false, true));
            case PE -> List.of(
                new Spec(DocumentType.INVOICE, IdType.RUC, List.of(TaxCode.IGV18), false, false),
                new Spec(DocumentType.INVOICE, IdType.DNI, List.of(TaxCode.EXONERADO, TaxCode.IGV18), false, false),
                new Spec(DocumentType.INVOICE, IdType.CE, List.of(TaxCode.IGV18), false, false),
                new Spec(DocumentType.CREDIT_NOTE, IdType.PAS, List.of(TaxCode.IGV18), false, false),
                new Spec(DocumentType.DEBIT_NOTE, IdType.RUC, List.of(TaxCode.EXONERADO), false, false),
                new Spec(DocumentType.INVOICE, IdType.DNI, List.of(TaxCode.IGV18), true, false),
                new Spec(DocumentType.INVOICE, IdType.RUC, List.of(TaxCode.IGV18, TaxCode.IGV18), false, true));
            case XX -> List.of(
                new Spec(DocumentType.INVOICE, IdType.TIN, List.of(TaxCode.VAT12, TaxCode.ZERO), false, false),
                new Spec(DocumentType.INVOICE, IdType.PAS, List.of(TaxCode.VAT12), false, false),
                new Spec(DocumentType.CREDIT_NOTE, IdType.TIN, List.of(TaxCode.VAT12), false, false),
                new Spec(DocumentType.DEBIT_NOTE, IdType.PAS, List.of(TaxCode.ZERO), false, false),
                new Spec(DocumentType.INVOICE, IdType.TIN, List.of(TaxCode.VAT12), true, false),
                new Spec(DocumentType.INVOICE, IdType.TIN, List.of(TaxCode.ZERO, TaxCode.VAT12), false, true));
        };
        List<InvoiceRequest> out = new ArrayList<>();
        for (Spec s : specs) out.add(g.next(country, s));
        return out;
    }

    /** Defectos de datos que se pueden inyectar en el canónico. Los demás tipos solo existen en el mapeo. */
    public enum DataDefect {
        /** D1: identificación de persona con letras (incumple el formato del catálogo). Lo bloquea el kit. */
        D1_ID_FORMAT,
        /** D4: correo del adquirente ausente (obligatorio en el perfil). Lo bloquea el kit. */
        D4_MISSING_EMAIL,
        /** D2: valor de línea que no es cantidad × precio. Lo rechazan las invariantes de la ingesta. */
        D2_LINE_AMOUNT,
        /** D3: moneda en minúsculas. La rechaza el esquema canónico en la ingesta. */
        D3_CURRENCY_CASE
    }

    /** Comprobante CO con un defecto de datos (como JSON, porque algunos no son representables como canónico válido). */
    public ObjectNode defective(DataDefect d) {
        InvoiceRequest inv = next(Country.CO, new Spec(DocumentType.INVOICE, IdType.CC, List.of(TaxCode.IVA19), false, false));
        ObjectNode n = ContractJson.mapper().valueToTree(inv);
        switch (d) {
            case D1_ID_FORMAT -> ((ObjectNode) n.get("customer")).put("taxId", "12AB34567");
            case D4_MISSING_EMAIL -> ((ObjectNode) n.get("customer")).putNull("email");
            case D2_LINE_AMOUNT -> ((ObjectNode) n.get("lines").get(0)).put("amount", "1.00");
            case D3_CURRENCY_CASE -> n.put("currency", "cop");
        }
        return n;
    }

    private IdType customerIdType(Country c) {
        return switch (c) {
            case CO -> pick(IdType.NIT, 40, IdType.CC, 40, IdType.CE, 10, IdType.PAS, 10);
            case PE -> pick(IdType.RUC, 50, IdType.DNI, 35, IdType.CE, 10, IdType.PAS, 5);
            case XX -> pick(IdType.TIN, 80, IdType.PAS, 20, IdType.PAS, 0);
        };
    }

    private TaxCode taxCode(Country c) {
        return switch (c) {
            case CO -> pick(TaxCode.IVA19, 70, TaxCode.IVA5, 20, TaxCode.EXENTO, 10);
            case PE -> pick(TaxCode.IGV18, 85, TaxCode.EXONERADO, 15, TaxCode.EXONERADO, 0);
            case XX -> pick(TaxCode.VAT12, 85, TaxCode.ZERO, 15, TaxCode.ZERO, 0);
        };
    }

    private String customerId(IdType t) {
        return switch (t) {
            case NIT -> TaxIds.nit(rng);
            case CC -> TaxIds.cc(rng);
            case RUC -> TaxIds.ruc(rng);
            case DNI -> TaxIds.dni(rng);
            case TIN -> TaxIds.tin(rng);
            case CE -> TaxIds.alnum(rng, 9, 12);
            case PAS -> TaxIds.alnum(rng, 7, 12);
        };
    }

    @SafeVarargs
    private <T> T pick(Object... valuesAndWeights) {
        int total = 0;
        for (int i = 1; i < valuesAndWeights.length; i += 2) total += (Integer) valuesAndWeights[i];
        int r = rng.nextInt(total);
        for (int i = 0; i < valuesAndWeights.length; i += 2) {
            r -= (Integer) valuesAndWeights[i + 1];
            @SuppressWarnings("unchecked") T v = (T) valuesAndWeights[i];
            if (r < 0) return v;
        }
        @SuppressWarnings("unchecked") T last = (T) valuesAndWeights[valuesAndWeights.length - 2];
        return last;
    }
}
