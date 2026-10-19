package org.veriagent.core.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.veriagent.core.correlation.CorrelationContext;
import org.veriagent.core.security.Actor;
import org.veriagent.core.security.CurrentActor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Registro de auditoría de solo anexado ({@code audit_log}). No expone operaciones de modificación ni de
 * borrado: lo registrado no se puede alterar desde la aplicación. El actor sale siempre del token.
 */
public class AuditService {
    private final JdbcClient jdbc;

    public AuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Entry(long id, String actor, String actorRole, String action, String target, String details,
                        String correlationId, Instant occurredAt) {}

    public void record(String action, String target, String details) {
        Actor a = CurrentActor.get();
        jdbc.sql("""
            INSERT INTO audit_log (actor, actor_role, action, target, details, correlation_id, occurred_at)
            VALUES (:actor, :role, :action, :target, :details, :corr, :now)""")
            .param("actor", a.subject()).param("role", a.role().name()).param("action", action)
            .param("target", target).param("details", details == null || details.length() <= 4000 ? details : details.substring(0, 4000))
            .param("corr", CorrelationContext.current()).param("now", Timestamp.from(Instant.now()))
            .update();
    }

    public List<Entry> forTarget(String target) {
        return jdbc.sql("SELECT * FROM audit_log WHERE target = :t ORDER BY id").param("t", target).query(ROW).list();
    }

    public List<Entry> recent(int limit) {
        return jdbc.sql("SELECT * FROM audit_log ORDER BY id DESC LIMIT :n").param("n", Math.max(1, Math.min(limit, 500)))
            .query(ROW).list();
    }

    private static final org.springframework.jdbc.core.RowMapper<Entry> ROW = (rs, i) -> new Entry(
        rs.getLong("id"), rs.getString("actor"), rs.getString("actor_role"), rs.getString("action"),
        rs.getString("target"), rs.getString("details"), rs.getString("correlation_id"),
        rs.getTimestamp("occurred_at").toInstant());
}
