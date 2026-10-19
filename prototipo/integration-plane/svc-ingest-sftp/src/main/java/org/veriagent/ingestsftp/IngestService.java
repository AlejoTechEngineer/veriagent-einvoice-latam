package org.veriagent.ingestsftp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.ContractValidator;
import org.veriagent.contracts.events.EventTypes;
import org.veriagent.contracts.events.Payloads;
import org.veriagent.contracts.events.Topics;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.contracts.model.InvoiceRules;
import org.veriagent.core.VeriagentProperties;
import org.veriagent.core.audit.AuditService;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.bus.Events;
import org.veriagent.core.correlation.CorrelationContext;
import org.veriagent.core.dlq.DlqRepository;
import org.veriagent.core.io.SafeFiles;
import org.veriagent.core.ledger.InvoiceLedger;
import org.veriagent.core.security.CurrentActor;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ingest-sftp (tabla 11): recibe archivos del buzón, descomprime con límites (protección contra bombas de
 * descompresión), valida cada comprobante con el esquema canónico y sus invariantes antes de procesarlo, lo
 * registra de forma idempotente y lo publica en {@code {pais}.invoice.request.v1}. Lo inválido va a la DLQ.
 */
public class IngestService {
    static final String PRODUCER = "ingest-sftp";
    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final ContractValidator validator = new ContractValidator();
    private final InvoiceLedger ledger;
    private final DlqRepository dlq;
    private final EventBus bus;
    private final AuditService audit;
    private final JdbcClient jdbc;
    private final VeriagentProperties.Limits limits;
    private final IngestProperties props;

    public IngestService(InvoiceLedger ledger, DlqRepository dlq, EventBus bus, AuditService audit, JdbcClient jdbc,
                         VeriagentProperties core, IngestProperties props) {
        this.ledger = ledger;
        this.dlq = dlq;
        this.bus = bus;
        this.audit = audit;
        this.jdbc = jdbc;
        this.limits = core.getLimits();
        this.props = props;
    }

    public record Summary(String file, String sha256, int received, int accepted, int duplicates, int rejected) {}

    /** Archivo rechazado entero (tamaño, formato o compresión fuera de límites). */
    public static class RejectedFileException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public RejectedFileException(String message) {
            super(message);
        }
    }

    public Summary ingest(String originalName, byte[] content) {
        String file = SafeFiles.sanitize(originalName);
        if (content.length > limits.getMaxBatchBytes()) {
            throw new RejectedFileException("El archivo supera el tamaño máximo de " + limits.getMaxBatchBytes() + " bytes");
        }
        List<Item> items = new ArrayList<>();
        String lower = file.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) {
            unzip(file, content, items);
        } else if (lower.endsWith(".json") || lower.endsWith(".jsonl")) {
            parse(file, new String(content, StandardCharsets.UTF_8), lower.endsWith(".jsonl"), items);
        } else {
            throw new RejectedFileException("Formato no admitido: " + file + " (se admiten .json, .jsonl y .zip)");
        }
        if (items.size() > limits.getMaxInvoicesPerBatch()) {
            throw new RejectedFileException("El lote supera el máximo de " + limits.getMaxInvoicesPerBatch() + " comprobantes");
        }
        int accepted = 0, duplicates = 0, rejected = 0;
        for (Item it : items) {
            switch (process(file, it)) {
                case ACCEPTED -> accepted++;
                case DUPLICATE -> duplicates++;
                case REJECTED -> rejected++;
            }
        }
        Summary s = new Summary(file, sha256(content), items.size(), accepted, duplicates, rejected);
        jdbc.sql("""
            INSERT INTO ingest_file (file_name, sha256, received, accepted, duplicates, rejected, actor, correlation_id, processed_at)
            VALUES (:f, :sha, :r, :a, :d, :x, :actor, :corr, :now)""")
            .param("f", file).param("sha", s.sha256()).param("r", s.received()).param("a", accepted).param("d", duplicates)
            .param("x", rejected).param("actor", CurrentActor.get().subject()).param("corr", CorrelationContext.current())
            .param("now", Timestamp.from(Instant.now())).update();
        audit.record("INGEST_FILE", "archivo:" + file, "recibidos=" + s.received() + " aceptados=" + accepted
            + " duplicados=" + duplicates + " rechazados=" + rejected);
        log.info("Ingesta de {}: {} recibidos, {} aceptados, {} duplicados, {} rechazados", file, s.received(), accepted, duplicates, rejected);
        return s;
    }

    private enum Result { ACCEPTED, DUPLICATE, REJECTED }

    private record Item(String ref, JsonNode node, String raw) {}

    private Result process(String file, Item it) {
        if (it.node() == null) return reject(it, "No es JSON válido");
        List<String> errors = validator.validate(ContractValidator.CANONICAL, it.node());
        if (!errors.isEmpty()) return reject(it, "No cumple el esquema canónico: " + String.join("; ", errors));
        InvoiceRequest inv;
        try {
            inv = ContractJson.mapper().treeToValue(it.node(), InvoiceRequest.class);
        } catch (RuntimeException e) {
            return reject(it, "No se pudo leer el comprobante: " + e.getMessage());
        }
        List<String> violations = InvoiceRules.violations(inv);
        if (!violations.isEmpty()) return reject(it, "Invariantes incumplidas: " + String.join("; ", violations));
        try {
            return CorrelationContext.call(inv.correlationId(), () -> {
                var reg = ledger.register(inv, Payloads.Channel.BATCH, ContractJson.mapper().writeValueAsString(it.node()), file);
                if (!reg.created()) return Result.DUPLICATE;
                bus.publish(Topics.invoiceRequest(inv.country()), Events.of(EventTypes.INVOICE_REQUESTED, inv.country().name(),
                    inv.invoiceKey(), PRODUCER, new Payloads.InvoiceRequested(Payloads.Channel.BATCH, file, inv)));
                return Result.ACCEPTED;
            });
        } catch (IllegalArgumentException e) {
            return reject(it, e.getMessage());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Result reject(Item it, String error) {
        String country = it.node() != null && it.node().has("country") ? it.node().get("country").asString() : null;
        if (country != null && !country.matches("CO|PE|XX")) country = null;
        dlq.open(null, it.ref(), country, null, "INGEST", "NON_RECOVERABLE", 0, error, null,
            it.raw() == null ? null : it.raw().length() > 20_000 ? it.raw().substring(0, 20_000) : it.raw());
        return Result.REJECTED;
    }

    private void parse(String ref, String text, boolean jsonl, List<Item> out) {
        if (jsonl) {
            String[] lines = text.split("\\R");
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].isBlank()) out.add(item(ref + "#" + (i + 1), lines[i]));
            }
            return;
        }
        JsonNode root;
        try {
            root = ContractJson.mapper().readTree(text);
        } catch (RuntimeException e) {
            out.add(new Item(ref + "#1", null, text.length() > 2000 ? text.substring(0, 2000) : text));
            return;
        }
        JsonNode arr = root.isArray() ? root : root.has("invoices") ? root.get("invoices") : null;
        if (arr == null) {
            out.add(new Item(ref + "#1", root, text));
            return;
        }
        int i = 0;
        for (JsonNode n : arr) out.add(new Item(ref + "#" + (++i), n, ContractJson.mapper().writeValueAsString(n)));
    }

    private Item item(String ref, String raw) {
        try {
            return new Item(ref, ContractJson.mapper().readTree(raw), raw);
        } catch (RuntimeException e) {
            return new Item(ref, null, raw);
        }
    }

    /** Descompresión acotada: número de entradas, tamaño total descomprimido y nombres seguros. */
    void unzip(String file, byte[] zip, List<Item> out) {
        long total = 0;
        int entries = 0;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (++entries > props.getMaxZipEntries()) throw new RejectedFileException("El ZIP tiene demasiadas entradas");
                String name = e.getName();
                if (e.isDirectory()) continue;
                if (name.contains("..") || name.startsWith("/") || name.startsWith("\\") || name.contains(":")) {
                    throw new RejectedFileException("Nombre de entrada no permitido en el ZIP: " + name);
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".json") || lower.endsWith(".jsonl"))) continue;
                byte[] data = readBounded(z, limits.getMaxUncompressedBytes() - total);
                total += data.length;
                parse(file + "!" + SafeFiles.sanitize(name), new String(data, StandardCharsets.UTF_8), lower.endsWith(".jsonl"), out);
            }
        } catch (IOException e) {
            throw new RejectedFileException("ZIP inválido: " + e.getMessage());
        }
    }

    private static byte[] readBounded(InputStream in, long remaining) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        long read = 0;
        while ((n = in.read(buf)) > 0) {
            read += n;
            if (read > remaining) throw new RejectedFileException("El ZIP supera el tamaño máximo descomprimido (posible bomba de descompresión)");
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
