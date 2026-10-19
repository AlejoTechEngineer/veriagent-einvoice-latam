package org.veriagent.core.dlq;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.veriagent.core.correlation.CorrelationContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Entradas de la cuarentena ({@code dlq_entry}). Comparten la tabla la ingesta y el gestor de la DLQ. */
public class DlqRepository {
    public enum Status { OPEN, REPROCESSED, DISCARDED }

    public record Entry(long id, String invoiceKey, String sourceRef, String country, String provider, String stage,
                        String category, int attempts, String lastError, String findings, String rawPayload,
                        Status status, String triage, String resolvedBy, String resolution, String correlationId,
                        Instant createdAt, Instant resolvedAt) {}

    private final JdbcClient jdbc;

    public DlqRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Registra una entrada abierta. Si el comprobante ya tiene una entrada abierta, la actualiza en lugar de duplicarla. */
    public long open(String invoiceKey, String sourceRef, String country, String provider, String stage, String category,
                     int attempts, String lastError, String findings, String rawPayload) {
        if (invoiceKey != null) {
            Optional<Entry> existing = openFor(invoiceKey);
            if (existing.isPresent()) {
                jdbc.sql("UPDATE dlq_entry SET stage = :s, category = :c, attempts = :a, last_error = :e, findings = COALESCE(:f, findings) WHERE id = :id")
                    .param("s", stage).param("c", category).param("a", attempts).param("e", trunc(lastError))
                    .param("f", findings).param("id", existing.get().id()).update();
                return existing.get().id();
            }
        }
        jdbc.sql("""
            INSERT INTO dlq_entry (invoice_key, source_ref, country, provider, stage, category, attempts, last_error, findings,
              raw_payload, status, correlation_id, created_at)
            VALUES (:k, :ref, :country, :provider, :stage, :cat, :att, :err, :f, :raw, 'OPEN', :corr, :now)""")
            .param("k", invoiceKey).param("ref", sourceRef).param("country", country).param("provider", provider)
            .param("stage", stage).param("cat", category).param("att", attempts).param("err", trunc(lastError))
            .param("f", findings).param("raw", rawPayload).param("corr", CorrelationContext.current())
            .param("now", Timestamp.from(Instant.now())).update();
        return jdbc.sql("SELECT MAX(id) FROM dlq_entry WHERE source_ref = :ref").param("ref", sourceRef).query(Long.class).single();
    }

    public Optional<Entry> find(long id) {
        return jdbc.sql("SELECT * FROM dlq_entry WHERE id = :id").param("id", id).query(ROW).optional();
    }

    public Optional<Entry> openFor(String invoiceKey) {
        return jdbc.sql("SELECT * FROM dlq_entry WHERE invoice_key = :k AND status = 'OPEN' ORDER BY id DESC LIMIT 1")
            .param("k", invoiceKey).query(ROW).optional();
    }

    public List<Entry> list(Status status, int limit) {
        if (status == null) {
            return jdbc.sql("SELECT * FROM dlq_entry ORDER BY id DESC LIMIT :n").param("n", limit).query(ROW).list();
        }
        return jdbc.sql("SELECT * FROM dlq_entry WHERE status = :s ORDER BY id DESC LIMIT :n")
            .param("s", status.name()).param("n", limit).query(ROW).list();
    }

    public long count(Status status) {
        return jdbc.sql("SELECT COUNT(*) FROM dlq_entry WHERE status = :s").param("s", status.name()).query(Long.class).single();
    }

    /** Entradas abiertas que no corresponden a un comprobante registrado (entradas inválidas de la ingesta). */
    public long countOpenWithoutInvoice() {
        return jdbc.sql("SELECT COUNT(*) FROM dlq_entry WHERE status = 'OPEN' AND invoice_key IS NULL").query(Long.class).single();
    }

    public void resolve(long id, Status status, String actor, String resolution) {
        int n = jdbc.sql("UPDATE dlq_entry SET status = :s, resolved_by = :a, resolution = :r, resolved_at = :now WHERE id = :id AND status = 'OPEN'")
            .param("s", status.name()).param("a", actor).param("r", trunc(resolution))
            .param("now", Timestamp.from(Instant.now())).param("id", id).update();
        if (n == 0) throw new IllegalStateException("La entrada " + id + " no está abierta");
    }

    public void saveTriage(long id, String triageJson) {
        jdbc.sql("UPDATE dlq_entry SET triage = :t WHERE id = :id").param("t", triageJson).param("id", id).update();
    }

    private static String trunc(String s) {
        return s == null ? "" : s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    private static final RowMapper<Entry> ROW = (rs, i) -> new Entry(
        rs.getLong("id"), rs.getString("invoice_key"), rs.getString("source_ref"), rs.getString("country"),
        rs.getString("provider"), rs.getString("stage"), rs.getString("category"), rs.getInt("attempts"),
        rs.getString("last_error"), rs.getString("findings"), rs.getString("raw_payload"),
        Status.valueOf(rs.getString("status")), rs.getString("triage"), rs.getString("resolved_by"),
        rs.getString("resolution"), rs.getString("correlation_id"), rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant());
}
