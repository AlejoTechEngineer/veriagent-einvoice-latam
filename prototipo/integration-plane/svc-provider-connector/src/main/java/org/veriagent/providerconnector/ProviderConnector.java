package org.veriagent.providerconnector;

import io.github.resilience4j.retry.Retry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.core.retry.RetryPolicies;
import org.veriagent.provider.DocumentPreparer;
import org.veriagent.provider.client.ProviderClient;
import org.veriagent.provider.client.ProviderProtocol.Decision;

import java.util.Map;

/**
 * provider-connector-{proveedor} (tabla 11): conector síncrono con estrategias por proveedor. Verifica con el kit
 * ANTES de invocar al proveedor (ADR-05) y reintenta con espera creciente solo los fallos recuperables.
 */
@Component
@ConditionalOnProperty(prefix = "veriagent.services", name = "provider-connector", havingValue = "true", matchIfMissing = true)
public class ProviderConnector {
    private final DocumentPreparer preparer;
    private final ProviderClient client;
    private final RetryPolicies retries;

    public ProviderConnector(DocumentPreparer preparer, ProviderClient client, RetryPolicies retries) {
        this.preparer = preparer;
        this.client = client;
        this.retries = retries;
    }

    public enum Outcome { AUTHORIZED, REJECTED, KIT_BLOCKED, UNAVAILABLE, NO_MAPPING }

    /** Resultado del envío síncrono. */
    public record Result(Outcome outcome, DocumentPreparer.Prepared prepared, Decision decision, int attempts, String error) {}

    /** Formato del contenido en el sobre según el perfil del kit. */
    static String format(String kitProfile) {
        return switch (kitProfile) {
            case "alfa" -> "xml";
            case "gamma" -> "json";
            default -> "seg";
        };
    }

    public Result send(InvoiceRequest inv, String provider, Map<String, String> extraHeaders) {
        return sendVerified(inv, provider, extraHeaders, p -> {});
    }

    /**
     * Igual que {@link #send}, pero ejecuta {@code beforeSend} solo cuando el documento pasó el kit y justo antes de
     * invocar al proveedor (la fachada registra ahí el estado SENT).
     */
    public Result sendVerified(InvoiceRequest inv, String provider, Map<String, String> extraHeaders,
                               java.util.function.Consumer<DocumentPreparer.Prepared> beforeSend) {
        DocumentPreparer.Prepared p;
        try {
            p = preparer.prepare(inv, provider);
        } catch (DocumentPreparer.MissingMappingException e) {
            return new Result(Outcome.NO_MAPPING, null, null, 0, e.getMessage());
        }
        if (!p.passed()) return new Result(Outcome.KIT_BLOCKED, p, null, 0, null);
        beforeSend.accept(p);
        Retry retry = retries.providerCalls("sincrono-" + provider);
        int[] attempts = {0};
        try {
            Decision d = Retry.decorateSupplier(retry, () -> {
                attempts[0]++;
                return client.submit(provider, inv.invoiceKey(), format(p.kitProfile()), p.content(), extraHeaders);
            }).get();
            return new Result(d.authorized() ? Outcome.AUTHORIZED : Outcome.REJECTED, p, d, attempts[0], null);
        } catch (RetryPolicies.RecoverableException e) {
            return new Result(Outcome.UNAVAILABLE, p, null, attempts[0], e.getMessage());
        }
    }
}
