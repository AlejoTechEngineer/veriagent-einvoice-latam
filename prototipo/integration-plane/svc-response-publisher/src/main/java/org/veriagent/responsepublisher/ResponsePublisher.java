package org.veriagent.responsepublisher;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.events.EventEnvelope;
import org.veriagent.contracts.events.Payloads;
import org.veriagent.contracts.events.Topics;
import org.veriagent.core.VeriagentProperties;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.bus.Events;
import org.veriagent.core.io.SafeFiles;
import org.veriagent.core.ledger.InvoiceLedger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * response-publisher (tabla 11): devuelve la respuesta al sistema de origen en su formato. Canal por lotes: una
 * línea JSON por comprobante en el archivo de respuestas del lote de origen (SFTP de salida). Canal síncrono: la
 * respuesta ya viajó en la llamada; se registra la entrega.
 */
@Component
@ConditionalOnProperty(prefix = "veriagent.services", name = "response-publisher", havingValue = "true", matchIfMissing = true)
public class ResponsePublisher {
    static final String GROUP = "response-publisher";

    private final EventBus bus;
    private final InvoiceLedger ledger;
    private final JdbcClient jdbc;
    private final Path outbox;
    private EventBus.Subscription subscription;

    public ResponsePublisher(EventBus bus, InvoiceLedger ledger, JdbcClient jdbc, VeriagentProperties core,
                             org.veriagent.provider.client.ProvidersProperties providers) {
        this.bus = bus;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.outbox = Path.of(core.getHome()).resolve(providers.getSftpRoot()).resolve("responses").toAbsolutePath().normalize();
    }

    @PostConstruct
    void start() {
        subscription = bus.subscribe(Topics.ALL_INVOICE_RESPONSES, GROUP, this::onResponse);
    }

    @PreDestroy
    void stop() {
        if (subscription != null) subscription.close();
    }

    synchronized void onResponse(String topic, EventEnvelope e) {
        var r = Events.payload(e, Payloads.InvoiceResponded.class);
        String target;
        if (r.channel() == Payloads.Channel.SYNC) {
            target = "respuesta HTTP de la fachada";
        } else {
            String source = ledger.find(e.invoiceKey()).map(x -> x.sourceFile()).orElse(null);
            String name = SafeFiles.sanitize((source == null ? "sin-origen" : source) + ".respuestas.jsonl");
            Path file = SafeFiles.resolveInside(outbox.resolve(e.country().toLowerCase()), name);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("invoiceKey", e.invoiceKey());
            line.put("correlationId", e.correlationId());
            line.put("status", r.status());
            line.put("authorizationNumber", r.authorizationNumber());
            line.put("reasonCode", r.reasonCode());
            line.put("reason", r.reason());
            line.put("findings", r.findings());
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, ContractJson.mapper().writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            target = outbox.relativize(file).toString().replace('\\', '/');
        }
        jdbc.sql("INSERT INTO response_delivery (invoice_key, status, channel, target, delivered_at) VALUES (:k, :s, :c, :t, :now)")
            .param("k", e.invoiceKey()).param("s", r.status().name()).param("c", r.channel().name()).param("t", target)
            .param("now", Timestamp.from(Instant.now())).update();
    }

    public Path outbox() { return outbox; }
}
