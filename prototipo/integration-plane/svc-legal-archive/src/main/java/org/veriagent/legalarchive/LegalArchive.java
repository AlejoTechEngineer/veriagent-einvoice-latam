package org.veriagent.legalarchive;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.veriagent.contracts.events.EventEnvelope;
import org.veriagent.contracts.events.Payloads;
import org.veriagent.contracts.events.Topics;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.bus.Events;
import org.veriagent.core.ledger.InvoiceLedger;
import org.veriagent.core.ledger.InvoiceRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * legal-archive (tabla 11 y ADR-07): archiva el documento autorizado en un almacén de solo anexado. Cada entrada
 * guarda la huella SHA-256 del objeto y se encadena con la anterior (huella de la entrada = SHA-256 de la secuencia,
 * la clave, la huella del objeto y la huella previa), de modo que cualquier alteración rompe la cadena. Es de mejor
 * esfuerzo: un fallo del almacenamiento no detiene la emisión y se reintenta en segundo plano.
 */
public class LegalArchive {
    static final String GROUP = "legal-archive";
    static final String GENESIS = "0".repeat(64);
    private static final Logger log = LoggerFactory.getLogger(LegalArchive.class);

    private final EventBus bus;
    private final InvoiceLedger ledger;
    private final JdbcClient jdbc;
    private final Path root;
    private final Map<String, Integer> retentionYears;
    private final ScheduledExecutorService retrier = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "archivo-reintentos");
        t.setDaemon(true);
        return t;
    });
    private EventBus.Subscription subscription;

    public LegalArchive(EventBus bus, InvoiceLedger ledger, JdbcClient jdbc, Path root, Map<String, Integer> retentionYears) {
        this.bus = bus;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.root = root;
        this.retentionYears = retentionYears;
    }

    public record Entry(long seq, String invoiceKey, String country, String objectPath, String objectSha256, String prevHash,
                        String entryHash, LocalDate retentionUntil, Instant archivedAt) {}

    @PostConstruct
    void start() {
        subscription = bus.subscribe(Topics.ALL_INVOICE_RESPONSES, GROUP, this::onResponse);
        retrier.scheduleWithFixedDelay(this::retryPending, 1, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        if (subscription != null) subscription.close();
        retrier.shutdownNow();
    }

    void onResponse(String topic, EventEnvelope e) {
        var r = Events.payload(e, Payloads.InvoiceResponded.class);
        if (r.status() != Payloads.FinalStatus.AUTHORIZED) return;
        tryArchive(e.invoiceKey());
    }

    /** Intenta archivar; si falla, deja el comprobante pendiente con reintento y no propaga el error. */
    void tryArchive(String invoiceKey) {
        try {
            archive(invoiceKey);
            jdbc.sql("DELETE FROM archive_pending WHERE invoice_key = :k").param("k", invoiceKey).update();
        } catch (Exception ex) {
            int attempts = jdbc.sql("SELECT attempts FROM archive_pending WHERE invoice_key = :k").param("k", invoiceKey)
                .query(Integer.class).optional().orElse(0) + 1;
            Instant next = Instant.now().plus(Duration.ofMillis(Math.min(60_000, 250L * (1L << Math.min(attempts, 8)))));
            if (attempts == 1) {
                jdbc.sql("INSERT INTO archive_pending (invoice_key, attempts, last_error, next_attempt_at) VALUES (:k, 1, :e, :n)")
                    .param("k", invoiceKey).param("e", trunc(ex.toString())).param("n", Timestamp.from(next)).update();
            } else {
                jdbc.sql("UPDATE archive_pending SET attempts = :a, last_error = :e, next_attempt_at = :n WHERE invoice_key = :k")
                    .param("a", attempts).param("e", trunc(ex.toString())).param("n", Timestamp.from(next)).param("k", invoiceKey).update();
            }
            log.warn("Archivo legal pendiente para {} (intento {}): {}", invoiceKey, attempts, ex.toString());
        }
    }

    void retryPending() {
        try {
            List<String> due = jdbc.sql("SELECT invoice_key FROM archive_pending WHERE next_attempt_at <= :now ORDER BY next_attempt_at LIMIT 100")
                .param("now", Timestamp.from(Instant.now())).query(String.class).list();
            due.forEach(this::tryArchive);
        } catch (RuntimeException ex) {
            log.warn("Reintentos del archivo legal: {}", ex.toString());
        }
    }

    /** Archiva el documento autorizado (idempotente: si ya está archivado no hace nada). */
    synchronized Optional<Entry> archive(String invoiceKey) throws IOException {
        Optional<Entry> existing = find(invoiceKey);
        if (existing.isPresent()) return existing;
        InvoiceRecord rec = ledger.find(invoiceKey).orElseThrow(() -> new IllegalStateException("Comprobante desconocido: " + invoiceKey));
        String content = rec.transformedPayload() != null ? rec.transformedPayload() : rec.canonicalPayload();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String objectSha = sha256(bytes);
        long seq = jdbc.sql("SELECT COALESCE(MAX(seq), 0) + 1 FROM legal_archive_entry").query(Long.class).single();
        String prev = jdbc.sql("SELECT entry_hash FROM legal_archive_entry WHERE seq = :s").param("s", seq - 1)
            .query(String.class).optional().orElse(GENESIS);
        LocalDate issued = rec.receivedAt().atZone(ZoneOffset.UTC).toLocalDate();
        String ext = content.startsWith("<") ? ".xml" : content.startsWith("{") ? ".json" : ".txt";
        Path rel = Path.of(rec.country(), String.valueOf(issued.getYear()), seq + "-" + objectSha.substring(0, 16) + ext);
        Path file = root.resolve(rel).normalize();
        if (!file.startsWith(root)) throw new IllegalStateException("Ruta de archivo fuera del almacén");
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) {
            // Un intento anterior escribió el objeto pero no llegó a registrarlo: se reutiliza solo si es idéntico.
            if (!sha256(Files.readAllBytes(file)).equals(objectSha)) {
                throw new IllegalStateException("Ya existe un objeto distinto en " + rel + ": no se sobrescribe un archivo legal");
            }
        } else {
            Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
        }
        file.toFile().setReadOnly();
        String entryHash = sha256((seq + "|" + invoiceKey + "|" + objectSha + "|" + prev).getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        LocalDate retention = issued.plusYears(retentionYears.getOrDefault(rec.country(), 5));
        jdbc.sql("""
            INSERT INTO legal_archive_entry (seq, invoice_key, country, object_path, object_sha256, prev_hash, entry_hash,
              retention_until, archived_at) VALUES (:seq, :k, :c, :p, :o, :prev, :h, :ret, :now)""")
            .param("seq", seq).param("k", invoiceKey).param("c", rec.country()).param("p", rel.toString().replace('\\', '/'))
            .param("o", objectSha).param("prev", prev).param("h", entryHash).param("ret", retention)
            .param("now", Timestamp.from(now)).update();
        return find(invoiceKey);
    }

    public Optional<Entry> find(String invoiceKey) {
        return jdbc.sql("SELECT * FROM legal_archive_entry WHERE invoice_key = :k").param("k", invoiceKey).query(ROW).optional();
    }

    public List<Entry> list(int limit) {
        return jdbc.sql("SELECT * FROM legal_archive_entry ORDER BY seq DESC LIMIT :n").param("n", limit).query(ROW).list();
    }

    public long pendingCount() {
        return jdbc.sql("SELECT COUNT(*) FROM archive_pending").query(Long.class).single();
    }

    /** Resultado de verificar la cadena completa. */
    public record Verification(boolean valid, int entries, Long brokenAtSeq, String problem) {}

    /** Recalcula la cadena y la huella de cada objeto almacenado. */
    public Verification verify() {
        List<Entry> all = jdbc.sql("SELECT * FROM legal_archive_entry ORDER BY seq").query(ROW).list();
        String prev = GENESIS;
        for (Entry e : all) {
            if (!e.prevHash().equals(prev)) return new Verification(false, all.size(), e.seq(), "La huella previa no coincide");
            String h = sha256((e.seq() + "|" + e.invoiceKey() + "|" + e.objectSha256() + "|" + e.prevHash()).getBytes(StandardCharsets.UTF_8));
            if (!h.equals(e.entryHash())) return new Verification(false, all.size(), e.seq(), "La huella de la entrada no coincide");
            try {
                byte[] bytes = Files.readAllBytes(root.resolve(e.objectPath()));
                if (!sha256(bytes).equals(e.objectSha256())) {
                    return new Verification(false, all.size(), e.seq(), "El objeto almacenado fue alterado");
                }
            } catch (IOException ex) {
                return new Verification(false, all.size(), e.seq(), "Objeto no encontrado: " + e.objectPath());
            }
            prev = e.entryHash();
        }
        return new Verification(true, all.size(), null, null);
    }

    public Path root() { return root; }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String trunc(String s) {
        return s == null ? null : s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    private static final org.springframework.jdbc.core.RowMapper<Entry> ROW = (rs, i) -> new Entry(
        rs.getLong("seq"), rs.getString("invoice_key"), rs.getString("country"), rs.getString("object_path"),
        rs.getString("object_sha256"), rs.getString("prev_hash"), rs.getString("entry_hash"),
        rs.getObject("retention_until", LocalDate.class), rs.getTimestamp("archived_at").toInstant());

    /** Solo para depuración de pruebas: claves archivadas. */
    List<String> keys() {
        return new ArrayList<>(jdbc.sql("SELECT invoice_key FROM legal_archive_entry ORDER BY seq").query(String.class).list());
    }
}
