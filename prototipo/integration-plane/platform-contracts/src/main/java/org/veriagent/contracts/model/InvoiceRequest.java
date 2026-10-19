package org.veriagent.contracts.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Modelo canónico InvoiceRequest v1 (apartado 4.4.4 del documento). Es el contrato que los sistemas de
 * origen entregan por los dos canales; su esquema JSON está en {@code contracts/canonical/invoice-request.v1.json}.
 */
public record InvoiceRequest(
    Country country,
    String correlationId,
    String documentNumber,
    DocumentType documentType,
    LocalDate issueDate,
    String currency,
    String originalDocumentNumber,
    Party supplier,
    Party customer,
    List<InvoiceLine> lines,
    Totals totals) {

    /**
     * Clave de idempotencia del comprobante: país, emisor, tipo y número. Dos envíos con la misma clave son
     * el mismo comprobante aunque cambie el identificador de correlación.
     */
    public String invoiceKey() {
        return (country.name() + ":" + supplier.taxId() + ":" + documentType.name() + ":" + documentNumber)
            .toUpperCase(Locale.ROOT);
    }
}
