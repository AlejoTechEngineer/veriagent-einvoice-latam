package org.veriagent.contracts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veriagent.contracts.events.EventEnvelope;
import org.veriagent.contracts.events.EventTypes;
import org.veriagent.contracts.events.Payloads;
import org.veriagent.contracts.events.Topics;
import org.veriagent.contracts.model.Country;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.contracts.model.InvoiceRules;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Pruebas de contrato: el canónico, los eventos y los topics cumplen lo que declara el documento. */
class ContractTest {
    private final ContractValidator validator = new ContractValidator();

    private static JsonNode sample() throws Exception {
        try (InputStream in = ContractTest.class.getResourceAsStream("/samples/invoice-co.json")) {
            return ContractJson.mapper().readTree(in);
        }
    }

    private static InvoiceRequest invoice() throws Exception {
        return ContractJson.mapper().treeToValue(sample(), InvoiceRequest.class);
    }

    private static JsonNode envelope(String type, Object payload, String invoiceKey) {
        EventEnvelope e = new EventEnvelope(UUID.randomUUID().toString(), type, 1, "corr-000001", invoiceKey, "CO",
            Instant.parse("2026-10-19T12:00:00Z"), "prueba", ContractJson.mapper().valueToTree(payload));
        return ContractJson.mapper().valueToTree(e);
    }

    @Test
    @DisplayName("El comprobante de muestra cumple el esquema canónico y sus invariantes")
    void sampleIsValid() throws Exception {
        assertEquals(List.of(), validator.validate(ContractValidator.CANONICAL, sample()));
        assertEquals(List.of(), InvoiceRules.violations(invoice()));
    }

    @Test
    @DisplayName("El esquema canónico rechaza campos ausentes, campos desconocidos y códigos fuera de catálogo")
    void canonicalRejectsInvalidDocuments() throws Exception {
        ObjectNode missing = (ObjectNode) sample().deepCopy();
        missing.remove("supplier");
        assertFalse(validator.validate(ContractValidator.CANONICAL, missing).isEmpty());

        ObjectNode extra = (ObjectNode) sample().deepCopy();
        extra.put("campoNuevoNoDeclarado", "x");
        assertFalse(validator.validate(ContractValidator.CANONICAL, extra).isEmpty());

        ObjectNode badTax = (ObjectNode) sample().deepCopy();
        ((ObjectNode) badTax.get("lines").get(0)).put("taxCode", "IVA21");
        assertFalse(validator.validate(ContractValidator.CANONICAL, badTax).isEmpty());

        ObjectNode badNumber = (ObjectNode) sample().deepCopy();
        badNumber.put("documentNumber", "../../etc/passwd");
        assertFalse(validator.validate(ContractValidator.CANONICAL, badNumber).isEmpty());
    }

    @Test
    @DisplayName("Lo que serializa el productor Java cumple el esquema canónico")
    void javaProducerMatchesSchema() throws Exception {
        JsonNode produced = ContractJson.mapper().valueToTree(invoice());
        assertEquals(List.of(), validator.validate(ContractValidator.CANONICAL, produced));
    }

    @Test
    @DisplayName("Las invariantes detectan importes, tributos del país y notas sin documento original")
    void invariantsDetectInconsistencies() throws Exception {
        ObjectNode tampered = (ObjectNode) sample().deepCopy();
        ((ObjectNode) tampered.get("totals")).put("taxAmount", "41.00");
        ((ObjectNode) tampered.get("lines").get(1)).put("taxCode", "IGV18").put("taxRate", "18.00");
        tampered.put("documentType", "CREDIT_NOTE");
        List<String> v = InvoiceRules.violations(ContractJson.mapper().treeToValue(tampered, InvoiceRequest.class));
        assertTrue(v.stream().anyMatch(s -> s.contains("impuesto")), v::toString);
        assertTrue(v.stream().anyMatch(s -> s.contains("IGV18") && s.contains("CO")), v::toString);
        assertTrue(v.stream().anyMatch(s -> s.contains("documento original")), v::toString);
    }

    @Test
    @DisplayName("La clave de idempotencia depende del país, el emisor, el tipo y el número, no de la correlación")
    void invoiceKeyIgnoresCorrelation() throws Exception {
        InvoiceRequest a = invoice();
        ObjectNode other = (ObjectNode) sample().deepCopy();
        other.put("correlationId", "otra-correlacion");
        InvoiceRequest b = ContractJson.mapper().treeToValue(other, InvoiceRequest.class);
        assertEquals("CO:900123456-8:INVOICE:SETP-990000001", a.invoiceKey());
        assertEquals(a.invoiceKey(), b.invoiceKey());
    }

    @Test
    @DisplayName("Los topics siguen la convención del documento")
    void topicsFollowConvention() {
        assertEquals("co.invoice.request.v1", Topics.invoiceRequest(Country.CO));
        assertEquals("pe.invoice.response.v1", Topics.invoiceResponse(Country.PE));
        assertEquals("xx.invoice.dlq.v1", Topics.invoiceDlq(Country.XX));
        assertEquals("co.alfa.request.v1", Topics.providerRequest(Country.CO, "Alfa"));
        assertEquals("pe.beta.response.v1", Topics.providerResponse(Country.PE, "beta"));
        for (String t : List.of("co.invoice.request.v1", "co.alfa.response.v1", Topics.MAPPING_ACTIVATED)) {
            assertTrue(Topics.isValid(t), t);
        }
        assertFalse(Topics.isValid("CO.invoice.request.v1"));
        assertFalse(Topics.isValid("invoice.co.alfa.request"));
        assertThrows(IllegalArgumentException.class, () -> Topics.providerRequest(Country.CO, "invoice"));
        assertTrue("co.alfa.request.v1".matches(Topics.ALL_PROVIDER_REQUESTS));
        assertFalse("co.invoice.request.v1".matches(Topics.ALL_PROVIDER_REQUESTS));
        assertEquals(Country.PE, Topics.countryOf("pe.beta.request.v1"));
    }

    @Test
    @DisplayName("Cada tipo de evento valida su sobre, su carga útil y el canónico embebido")
    void eventsMatchTheirSchemas() throws Exception {
        InvoiceRequest inv = invoice();
        var kf = new Payloads.KitFinding("R-D1-01", "D1", "Invoice/InvoiceTypeCode", "Código inexistente",
            "perfil-alfa-v2.1.md §3.2", "a".repeat(64), "2.1.0");
        List<JsonNode> events = List.of(
            envelope(EventTypes.INVOICE_REQUESTED, new Payloads.InvoiceRequested(Payloads.Channel.BATCH, "lote-1.json", inv), inv.invoiceKey()),
            envelope(EventTypes.PROVIDER_REQUESTED, new Payloads.ProviderRequested("alfa", "CO/*->alfa", inv), inv.invoiceKey()),
            envelope(EventTypes.PROVIDER_RESPONDED, new Payloads.ProviderResponded("alfa", "lote-1", Payloads.Outcome.AUTHORIZED,
                "00", "Autorizado", "AUT-1", "b".repeat(64), "PROVIDER"), inv.invoiceKey()),
            envelope(EventTypes.INVOICE_RESPONDED, new Payloads.InvoiceResponded(Payloads.FinalStatus.FAILED, Payloads.Channel.SYNC,
                "alfa", "KIT_BLOCKED", "Bloqueado por el kit", null, List.of(kf)), inv.invoiceKey()),
            envelope(EventTypes.INVOICE_DEAD_LETTERED, new Payloads.InvoiceDeadLettered("PROVIDER", Payloads.ErrorCategory.RECOVERABLE,
                5, "Proveedor no disponible", "beta", List.of()), inv.invoiceKey()),
            envelope(EventTypes.MAPPING_ACTIVATED, new Payloads.MappingActivated("alfa", "CO", 2, "c".repeat(64), "reviewer-a", "Propuesta aprobada"), null));
        for (JsonNode e : events) {
            assertEquals(List.of(), validator.validateEvent(e), e.get("eventType").asString());
        }
    }

    @Test
    @DisplayName("Un evento que transporta un canónico inválido no cumple el contrato")
    void eventWithInvalidInvoiceIsRejected() throws Exception {
        ObjectNode bad = (ObjectNode) sample().deepCopy();
        bad.remove("totals");
        ObjectNode payload = ContractJson.mapper().createObjectNode();
        payload.put("channel", "BATCH");
        payload.set("invoice", bad);
        ObjectNode e = (ObjectNode) envelope(EventTypes.INVOICE_REQUESTED, payload, "k");
        List<String> errors = validator.validateEvent(e);
        assertFalse(errors.isEmpty());
        assertTrue(errors.get(0).startsWith("payload/invoice"), errors::toString);

        ObjectNode noCorrelation = (ObjectNode) envelope(EventTypes.MAPPING_ACTIVATED,
            new Payloads.MappingActivated("alfa", "CO", 1, "c".repeat(64), "x", null), null);
        noCorrelation.remove("correlationId");
        assertFalse(validator.validateEvent(noCorrelation).isEmpty());
    }
}
