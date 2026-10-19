package org.veriagent.invoicefacade;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.ContractValidator;
import org.veriagent.contracts.events.Payloads.Channel;
import org.veriagent.contracts.events.Payloads.ErrorCategory;
import org.veriagent.contracts.events.Payloads.FinalStatus;
import org.veriagent.contracts.events.Payloads.KitFinding;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.contracts.model.InvoiceRules;
import org.veriagent.core.bus.FlowEvents;
import org.veriagent.core.correlation.CorrelationContext;
import org.veriagent.core.ledger.InvoiceLedger;
import org.veriagent.core.ledger.InvoiceLedger.Changes;
import org.veriagent.core.ledger.InvoiceRecord;
import org.veriagent.core.state.InvoiceState;
import org.veriagent.core.web.ApiErrors;
import org.veriagent.provider.client.ProviderProtocol;
import org.veriagent.provider.routing.RoutingTable;
import org.veriagent.providerconnector.ProviderConnector;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * invoice-facade (tabla 11): API REST síncrona con idempotencia. Valida el canónico (esquema e invariantes) antes
 * de procesar, registra el comprobante, lo enruta y lo envía por el conector, y responde en la misma llamada.
 * La clave de idempotencia es la del comprobante (país, emisor, tipo y número): un reenvío devuelve el registro
 * existente sin volver a enviarlo al proveedor.
 */
@RestController
@RequestMapping("/api/invoices")
@Tag(name = "Fachada síncrona", description = "Emisión de un comprobante en línea")
@ConditionalOnProperty(prefix = "veriagent.services", name = "invoice-facade", havingValue = "true", matchIfMissing = true)
public class InvoiceFacadeController {
    static final String PRODUCER = "invoice-facade";

    private final ContractValidator validator = new ContractValidator();
    private final InvoiceLedger ledger;
    private final RoutingTable routing;
    private final ProviderConnector connector;
    private final FlowEvents flow;
    private final boolean allowFaultHeaders;

    public InvoiceFacadeController(InvoiceLedger ledger, RoutingTable routing, ProviderConnector connector, FlowEvents flow,
                                   @Value("${veriagent.simulation.allow-fault-headers:false}") boolean allowFaultHeaders) {
        this.ledger = ledger;
        this.routing = routing;
        this.connector = connector;
        this.flow = flow;
        this.allowFaultHeaders = allowFaultHeaders;
    }

    @Operation(summary = "Emite un comprobante canónico de forma síncrona")
    @PostMapping
    @PreAuthorize("hasAnyRole('OPERATOR','SYSTEM')")
    public ResponseEntity<Map<String, Object>> issue(@RequestBody JsonNode body,
                                                     @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                     @RequestHeader(value = ProviderProtocol.FAULT_HEADER, required = false) String fault) {
        List<String> errors = validator.validate(ContractValidator.CANONICAL, body);
        if (!errors.isEmpty()) return ApiErrors.body(HttpStatus.BAD_REQUEST, "CONTRACT_VIOLATION", "El comprobante no cumple el esquema canónico", errors);
        InvoiceRequest inv = ContractJson.mapper().treeToValue(body, InvoiceRequest.class);
        List<String> violations = InvoiceRules.violations(inv);
        if (!violations.isEmpty()) return ApiErrors.body(HttpStatus.BAD_REQUEST, "INVARIANTS", "El comprobante no es coherente", violations);
        CorrelationContext.set(inv.correlationId());

        var reg = ledger.register(inv, Channel.SYNC, ContractJson.mapper().writeValueAsString(body));
        if (!reg.created()) {
            Map<String, Object> m = view(reg.record());
            m.put("duplicate", true);
            return ResponseEntity.ok(m);
        }
        String key = inv.invoiceKey();
        var route = routing.route(inv);
        if (route.isEmpty()) {
            flow.deadLetter(key, inv.country().name(), PRODUCER, "ROUTING", ErrorCategory.NON_RECOVERABLE, 0,
                "Sin regla de enrutamiento para " + inv.country() + "/" + inv.documentType(), null, null);
            return respond(HttpStatus.UNPROCESSABLE_ENTITY, key, "NO_ROUTE", "Sin regla de enrutamiento");
        }
        String provider = route.get().provider();
        Map<String, String> headers = allowFaultHeaders && fault != null ? Map.of(ProviderProtocol.FAULT_HEADER, fault) : Map.of();
        // El estado SENT se registra antes de llamar; si la verificación bloquea, nunca llega a enviarse.
        var result = connector.sendVerified(inv, provider, headers,
            prepared -> ledger.transition(key, InvoiceState.RECEIVED, InvoiceState.SENT,
                Changes.reason("Verificado por el kit y enviado al proveedor " + provider)
                    .withProvider(provider, prepared.mappingVersion()).withPayload(prepared.content())));
        return switch (result.outcome()) {
            case AUTHORIZED -> {
                ledger.transition(key, InvoiceState.SENT, InvoiceState.AUTHORIZED,
                    Changes.reason("Autorizado por " + provider).withAuthorization(result.decision().authorizationNumber()));
                flow.response(key, inv.country().name(), PRODUCER, FinalStatus.AUTHORIZED, Channel.SYNC, provider, "OK",
                    "Autorizado", result.decision().authorizationNumber(), null);
                yield respond(HttpStatus.OK, key, null, null);
            }
            case KIT_BLOCKED -> {
                List<KitFinding> f = result.prepared().verification().findings();
                ledger.transition(key, InvoiceState.RECEIVED, InvoiceState.FAILED,
                    Changes.reason("Bloqueado por el kit antes de invocar al proveedor")
                        .withProvider(provider, result.prepared().mappingVersion()).withPayload(result.prepared().content())
                        .withError("KIT_BLOCKED", f.get(0).ruleId() + ": " + f.get(0).message()).withFindings(FlowEvents.findingsJson(f)));
                flow.response(key, inv.country().name(), PRODUCER, FinalStatus.FAILED, Channel.SYNC, provider, "KIT_BLOCKED",
                    "Bloqueado por el kit de verificación", null, f);
                yield respond(HttpStatus.UNPROCESSABLE_ENTITY, key, "KIT_BLOCKED", "Bloqueado por el kit de verificación");
            }
            case REJECTED -> {
                var d = result.decision();
                ledger.transition(key, InvoiceState.SENT, InvoiceState.FAILED,
                    Changes.reason("Rechazado por " + provider + ": " + d.code()).withError("PROVIDER_REJECTED", d.code() + ": " + d.message()));
                flow.response(key, inv.country().name(), PRODUCER, FinalStatus.FAILED, Channel.SYNC, provider, "PROVIDER_REJECTED",
                    d.message(), null, null);
                flow.deadLetter(key, inv.country().name(), PRODUCER, "PROVIDER", ErrorCategory.NON_RECOVERABLE, result.attempts(),
                    "Rechazo del proveedor " + provider + " (" + d.code() + "): " + d.message(), provider, null);
                yield respond(HttpStatus.UNPROCESSABLE_ENTITY, key, "PROVIDER_REJECTED", d.message());
            }
            case UNAVAILABLE -> {
                flow.deadLetter(key, inv.country().name(), PRODUCER, "PROVIDER", ErrorCategory.RECOVERABLE, result.attempts(),
                    "Proveedor " + provider + " no disponible tras " + result.attempts() + " intentos: " + result.error(), provider, null);
                yield respond(HttpStatus.SERVICE_UNAVAILABLE, key, "PROVIDER_UNAVAILABLE",
                    "El proveedor no está disponible; el comprobante queda en cuarentena para reproceso");
            }
            case NO_MAPPING -> {
                flow.deadLetter(key, inv.country().name(), PRODUCER, "CONNECTOR", ErrorCategory.RECOVERABLE, 0, result.error(), provider, null);
                yield respond(HttpStatus.UNPROCESSABLE_ENTITY, key, "NO_MAPPING", result.error());
            }
        };
    }

    private ResponseEntity<Map<String, Object>> respond(HttpStatus status, String key, String reasonCode, String reason) {
        Map<String, Object> m = view(ledger.find(key).orElseThrow());
        m.put("duplicate", false);
        if (reasonCode != null) m.put("reasonCode", reasonCode);
        if (reason != null) m.put("reason", reason);
        return ResponseEntity.status(status).body(m);
    }

    static Map<String, Object> view(InvoiceRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("invoiceKey", r.invoiceKey());
        m.put("correlationId", r.correlationId());
        m.put("status", r.status().name());
        m.put("channel", r.channel());
        m.put("provider", r.provider());
        m.put("mappingVersion", r.mappingVersion());
        m.put("authorizationNumber", r.authorizationNumber());
        m.put("errorCode", r.errorCode());
        m.put("errorMessage", r.errorMessage());
        m.put("findings", FlowEvents.findingsFrom(r.findings()));
        return m;
    }
}
