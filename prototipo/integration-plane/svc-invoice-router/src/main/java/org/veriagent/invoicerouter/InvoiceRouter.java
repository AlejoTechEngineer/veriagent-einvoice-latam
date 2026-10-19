package org.veriagent.invoicerouter;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.veriagent.contracts.events.EventEnvelope;
import org.veriagent.contracts.events.EventTypes;
import org.veriagent.contracts.events.Payloads;
import org.veriagent.contracts.events.Topics;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.bus.Events;
import org.veriagent.core.bus.FlowEvents;
import org.veriagent.provider.routing.RoutingTable;

import java.util.concurrent.atomic.AtomicLong;

/**
 * invoice-router (tabla 11): consume {@code {pais}.invoice.request.v1}, elige el proveedor con la tabla de
 * enrutamiento versionada y publica en {@code {pais}.{proveedor}.request.v1}. Sin regla aplicable, el
 * comprobante va a la DLQ (no recuperable hasta que se corrija la tabla).
 */
@Component
@ConditionalOnProperty(prefix = "veriagent.services", name = "invoice-router", havingValue = "true", matchIfMissing = true)
public class InvoiceRouter {
    static final String PRODUCER = "invoice-router";
    private static final Logger log = LoggerFactory.getLogger(InvoiceRouter.class);

    private final EventBus bus;
    private final RoutingTable table;
    private final FlowEvents flow;
    private final AtomicLong routed = new AtomicLong();
    private final AtomicLong unrouted = new AtomicLong();
    private EventBus.Subscription subscription;

    public InvoiceRouter(EventBus bus, RoutingTable table, FlowEvents flow) {
        this.bus = bus;
        this.table = table;
        this.flow = flow;
    }

    @PostConstruct
    void start() {
        subscription = bus.subscribe(Topics.ALL_INVOICE_REQUESTS, PRODUCER, this::onRequest);
    }

    @PreDestroy
    void stop() {
        if (subscription != null) subscription.close();
    }

    void onRequest(String topic, EventEnvelope e) {
        var req = Events.payload(e, Payloads.InvoiceRequested.class);
        var inv = req.invoice();
        var route = table.route(inv);
        if (route.isEmpty()) {
            unrouted.incrementAndGet();
            log.warn("Sin regla de enrutamiento para {} ({} {})", inv.invoiceKey(), inv.country(), inv.documentType());
            flow.deadLetter(inv.invoiceKey(), inv.country().name(), PRODUCER, "ROUTING", Payloads.ErrorCategory.NON_RECOVERABLE,
                0, "Sin regla de enrutamiento para " + inv.country() + "/" + inv.documentType() + " en la tabla v" + table.version(),
                null, null);
            return;
        }
        var r = route.get();
        bus.publish(Topics.providerRequest(inv.country(), r.provider()),
            Events.of(EventTypes.PROVIDER_REQUESTED, inv.country().name(), inv.invoiceKey(), PRODUCER,
                new Payloads.ProviderRequested(r.provider(), r.ruleId() + "@v" + r.tableVersion(), inv)));
        routed.incrementAndGet();
    }

    public long routedCount() { return routed.get(); }
    public long unroutedCount() { return unrouted.get(); }
}
