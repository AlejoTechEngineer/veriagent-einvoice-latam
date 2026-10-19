package org.veriagent.contracts.events;

import org.veriagent.contracts.model.Country;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Convención de topics del documento (apartado 4.4.4 y tabla 11):
 * {@code {pais}.invoice.{request|response|dlq}.v{n}} y {@code {pais}.{proveedor}.{request|response}.v{n}},
 * en minúsculas y particionados por la clave del comprobante. Se añade {@code platform.mapping.activated.v1}
 * (desviación D-02).
 */
public final class Topics {
    public static final Pattern INVOICE_TOPIC = Pattern.compile("^[a-z]{2}\\.invoice\\.(request|response|dlq)\\.v[0-9]+$");
    public static final Pattern PROVIDER_TOPIC = Pattern.compile("^[a-z]{2}\\.(?!invoice\\.)[a-z]+\\.(request|response)\\.v[0-9]+$");
    public static final String MAPPING_ACTIVATED = "platform.mapping.activated.v1";

    /** Expresiones para suscribirse a un tipo de topic en todos los países y proveedores. */
    public static final String ALL_INVOICE_REQUESTS = "^[a-z]{2}\\.invoice\\.request\\.v1$";
    public static final String ALL_INVOICE_RESPONSES = "^[a-z]{2}\\.invoice\\.response\\.v1$";
    public static final String ALL_INVOICE_DLQ = "^[a-z]{2}\\.invoice\\.dlq\\.v1$";
    public static final String ALL_PROVIDER_REQUESTS = "^[a-z]{2}\\.(?!invoice\\.)[a-z]+\\.request\\.v1$";
    public static final String ALL_PROVIDER_RESPONSES = "^[a-z]{2}\\.(?!invoice\\.)[a-z]+\\.response\\.v1$";

    private Topics() {}

    public static String invoiceRequest(Country c) { return c.topicCode() + ".invoice.request.v1"; }
    public static String invoiceResponse(Country c) { return c.topicCode() + ".invoice.response.v1"; }
    public static String invoiceDlq(Country c) { return c.topicCode() + ".invoice.dlq.v1"; }
    public static String providerRequest(Country c, String provider) { return c.topicCode() + "." + provider(provider) + ".request.v1"; }
    public static String providerResponse(Country c, String provider) { return c.topicCode() + "." + provider(provider) + ".response.v1"; }

    public static boolean isValid(String topic) {
        return INVOICE_TOPIC.matcher(topic).matches() || PROVIDER_TOPIC.matcher(topic).matches()
            || MAPPING_ACTIVATED.equals(topic);
    }

    /** País del topic ({@code co.alfa.request.v1} → CO). */
    public static Country countryOf(String topic) {
        return Country.valueOf(topic.substring(0, 2).toUpperCase(Locale.ROOT));
    }

    private static String provider(String provider) {
        String v = provider.toLowerCase(Locale.ROOT);
        if (!v.matches("[a-z]+") || "invoice".equals(v)) throw new IllegalArgumentException("Proveedor inválido: " + provider);
        return v;
    }
}
