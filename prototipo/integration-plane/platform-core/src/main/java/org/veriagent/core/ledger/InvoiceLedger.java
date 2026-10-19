package org.veriagent.core.ledger;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.veriagent.contracts.events.Payloads.Channel;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.core.correlation.CorrelationContext;
import org.veriagent.core.security.CurrentActor;
import org.veriagent.core.state.IllegalTransitionException;
import org.veriagent.core.state.InvoiceState;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registro de comprobantes ({@code authorization_log} e {@code invoice_state_history}). Concentra tres reglas
 * transversales: idempotencia por clave de comprobante (restricción única), transiciones de estado válidas
 * (comprobadas en la propia sentencia UPDATE, a prueba de concurrencia) y línea de tiempo con actor y correlación.
 */
public class InvoiceLedger {
    private final JdbcClient jdbc;

    public InvoiceLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Resultado de registrar un comprobante: nuevo, o duplicado con el registro ya existente. */
    public record Registration(boolean created, InvoiceRecord record) {}

    /** Cambios opcionales que acompañan una transición. Los campos nulos no se modifican. */
    public record Changes(String provider, Integer mappingVersion, String transformedPayload, String errorCode,
                          String errorMessage, String authorizationNumber, boolean incrementRetry, String reason,
                          String findings) {
        public static Changes reason(String reason) {
            return new Changes(null, null, null, null, null, null, false, reason, null);
        }
        public Changes withProvider(String p, Integer v) {
            return new Changes(p, v, transformedPayload, errorCode, errorMessage, authorizationNumber, incrementRetry, reason, findings);
        }
        public Changes withPayload(String payload) {
            return new Changes(provider, mappingVersion, payload, errorCode, errorMessage, authorizationNumber, incrementRetry, reason, findings);
        }
        public Changes withError(String code, String message) {
            return new Changes(provider, mappingVersion, transformedPayload, code, message, authorizationNumber, incrementRetry, reason, findings);
        }
        public Changes withAuthorization(String number) {
            return new Changes(provider, mappingVersion, transformedPayload, errorCode, errorMessage, number, incrementRetry, reason, findings);
        }
        public Changes withFindings(String json) {
            return new Changes(provider, mappingVersion, transformedPayload, errorCode, errorMessage, authorizationNumber, incrementRetry, reason, json);
        }
        public Changes retried() {
            return new Changes(provider, mappingVersion, transformedPayload, errorCode, errorMessage, authorizationNumber, true, reason, findings);
        }
    }

    /** Registra el comprobante en RECEIVED. Si la clave ya existe, no crea nada y devuelve el existente. */
    @Transactional
    public Registration register(InvoiceRequest inv, Channel channel, String canonicalJson) {
        return register(inv, channel, canonicalJson, null);
    }

    /** Igual que {@link #register(InvoiceRequest, Channel, String)}, con el archivo de origen del lote. */
    @Transactional
    public Registration register(InvoiceRequest inv, Channel channel, String canonicalJson, String sourceFile) {
        Instant now = Instant.now();
        String key = inv.invoiceKey();
        String actor = CurrentActor.get().subject();
        try {
            jdbc.sql("""
                INSERT INTO authorization_log (invoice_key, correlation_id, invoice_number, document_type, country,
                  channel, status, canonical_payload, retry_count, last_actor, received_at, updated_at, source_file)
                VALUES (:key, :corr, :num, :type, :country, :channel, 'RECEIVED', :payload, 0, :actor, :now, :now, :src)""")
                .param("key", key).param("corr", inv.correlationId()).param("num", inv.documentNumber())
                .param("type", inv.documentType().name()).param("country", inv.country().name())
                .param("channel", channel.name()).param("payload", canonicalJson).param("actor", actor)
                .param("now", Timestamp.from(now)).param("src", sourceFile)
                .update();
        } catch (DuplicateKeyException e) {
            Optional<InvoiceRecord> existing = find(key);
            if (existing.isPresent()) return new Registration(false, existing.get());
            throw new IllegalArgumentException("El identificador de correlación " + inv.correlationId()
                + " ya pertenece a otro comprobante", e);
        }
        history(key, null, InvoiceState.RECEIVED, "Recibido por el canal " + channel, actor, inv.correlationId());
        return new Registration(true, find(key).orElseThrow());
    }

    /** Transición atómica: solo se aplica si el estado actual es {@code from} y la transición es válida. */
    @Transactional
    public InvoiceRecord transition(String invoiceKey, InvoiceState from, InvoiceState to, Changes c) {
        if (!from.canMoveTo(to)) throw new IllegalTransitionException(invoiceKey, from, to);
        Instant now = Instant.now();
        String actor = CurrentActor.get().subject();
        int rows = jdbc.sql("""
            UPDATE authorization_log SET status = :to, updated_at = :now, last_actor = :actor,
              provider = COALESCE(:provider, provider),
              mapping_version = COALESCE(:mv, mapping_version),
              transformed_payload = COALESCE(:payload, transformed_payload),
              error_code = CASE WHEN :to = 'RECEIVED' THEN NULL ELSE COALESCE(:ecode, error_code) END,
              error_message = CASE WHEN :to = 'RECEIVED' THEN NULL ELSE COALESCE(:emsg, error_message) END,
              authorization_number = COALESCE(:auth, authorization_number),
              findings = CASE WHEN :to = 'RECEIVED' THEN NULL ELSE COALESCE(:findings, findings) END,
              retry_count = retry_count + :inc,
              sent_at = CASE WHEN :to = 'SENT' THEN :now ELSE sent_at END,
              authorized_at = CASE WHEN :to = 'AUTHORIZED' THEN :now ELSE authorized_at END
            WHERE invoice_key = :key AND status = :from""")
            .param("to", to.name()).param("from", from.name()).param("key", invoiceKey)
            .param("now", Timestamp.from(now)).param("actor", actor)
            .param("provider", c.provider()).param("mv", c.mappingVersion()).param("payload", c.transformedPayload())
            .param("ecode", c.errorCode()).param("emsg", truncate(c.errorMessage(), 2000))
            .param("auth", c.authorizationNumber()).param("inc", c.incrementRetry() ? 1 : 0)
            .param("findings", c.findings())
            .update();
        if (rows == 0) {
            InvoiceState actual = find(invoiceKey).map(InvoiceRecord::status).orElse(null);
            throw new IllegalTransitionException(invoiceKey, actual, to);
        }
        history(invoiceKey, from, to, c.reason(), actor, CorrelationContext.current());
        return find(invoiceKey).orElseThrow();
    }

    public Optional<InvoiceRecord> find(String invoiceKey) {
        return jdbc.sql("SELECT * FROM authorization_log WHERE invoice_key = :k").param("k", invoiceKey)
            .query(ROW).optional();
    }

    public Optional<InvoiceRecord> findByCorrelation(String correlationId) {
        return jdbc.sql("SELECT * FROM authorization_log WHERE correlation_id = :c").param("c", correlationId)
            .query(ROW).optional();
    }

    /** Búsqueda con filtros opcionales, ordenada de lo más reciente a lo más antiguo. */
    public List<InvoiceRecord> search(InvoiceState status, String country, String provider, String channel,
                                      String numberLike, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM authorization_log WHERE 1=1");
        Map<String, Object> p = new LinkedHashMap<>();
        if (status != null) { sql.append(" AND status = :status"); p.put("status", status.name()); }
        if (country != null) { sql.append(" AND country = :country"); p.put("country", country); }
        if (provider != null) { sql.append(" AND provider = :provider"); p.put("provider", provider); }
        if (channel != null) { sql.append(" AND channel = :channel"); p.put("channel", channel); }
        if (numberLike != null) { sql.append(" AND invoice_number LIKE :num"); p.put("num", "%" + numberLike + "%"); }
        sql.append(" ORDER BY received_at DESC, id DESC LIMIT :limit OFFSET :offset");
        p.put("limit", Math.max(1, Math.min(limit, 500)));
        p.put("offset", Math.max(0, offset));
        return jdbc.sql(sql.toString()).params(p).query(ROW).list();
    }

    /** Conteo por estado (todos los estados aparecen, con cero si no hay comprobantes). */
    public Map<InvoiceState, Long> countByStatus() {
        Map<InvoiceState, Long> out = new LinkedHashMap<>();
        for (InvoiceState s : InvoiceState.values()) out.put(s, 0L);
        jdbc.sql("SELECT status, COUNT(*) AS n FROM authorization_log GROUP BY status")
            .query((rs, i) -> Map.entry(InvoiceState.valueOf(rs.getString("status")), rs.getLong("n")))
            .list().forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public long count() {
        return jdbc.sql("SELECT COUNT(*) FROM authorization_log").query(Long.class).single();
    }

    /** Línea de tiempo de estados del comprobante. */
    public List<StateChange> history(String invoiceKey) {
        return jdbc.sql("SELECT * FROM invoice_state_history WHERE invoice_key = :k ORDER BY occurred_at, id")
            .param("k", invoiceKey)
            .query((rs, i) -> new StateChange(rs.getString("from_status") == null ? null : InvoiceState.valueOf(rs.getString("from_status")),
                InvoiceState.valueOf(rs.getString("to_status")), rs.getString("reason"), rs.getString("actor"),
                rs.getString("correlation_id"), rs.getTimestamp("occurred_at").toInstant()))
            .list();
    }

    public record StateChange(InvoiceState from, InvoiceState to, String reason, String actor, String correlationId,
                              Instant occurredAt) {}

    private void history(String key, InvoiceState from, InvoiceState to, String reason, String actor, String corr) {
        jdbc.sql("""
            INSERT INTO invoice_state_history (invoice_key, from_status, to_status, reason, actor, correlation_id, occurred_at)
            VALUES (:k, :f, :t, :r, :a, :c, :now)""")
            .param("k", key).param("f", from == null ? null : from.name()).param("t", to.name())
            .param("r", truncate(reason, 2000)).param("a", actor).param("c", corr)
            .param("now", Timestamp.from(Instant.now()))
            .update();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static final RowMapper<InvoiceRecord> ROW = (rs, i) -> new InvoiceRecord(
        rs.getLong("id"), rs.getString("invoice_key"), rs.getString("correlation_id"), rs.getString("invoice_number"),
        rs.getString("document_type"), rs.getString("country"), rs.getString("channel"), rs.getString("provider"),
        rs.getObject("mapping_version", Integer.class), InvoiceState.valueOf(rs.getString("status")),
        rs.getString("canonical_payload"), rs.getString("transformed_payload"), rs.getString("error_code"),
        rs.getString("error_message"), rs.getString("authorization_number"), rs.getInt("retry_count"),
        rs.getString("last_actor"), ts(rs, "received_at"), ts(rs, "sent_at"), ts(rs, "authorized_at"),
        ts(rs, "updated_at"), rs.getString("findings"), rs.getString("source_file"));

    /** Utilidad para pruebas y escenarios: claves de todos los comprobantes en un estado. */
    public List<String> keysIn(InvoiceState status) {
        return new ArrayList<>(jdbc.sql("SELECT invoice_key FROM authorization_log WHERE status = :s ORDER BY id")
            .param("s", status.name()).query(String.class).list());
    }
}
