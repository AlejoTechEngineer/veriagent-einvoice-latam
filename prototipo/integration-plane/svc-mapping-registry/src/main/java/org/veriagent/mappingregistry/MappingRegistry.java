package org.veriagent.mappingregistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.events.EventTypes;
import org.veriagent.contracts.events.Payloads;
import org.veriagent.contracts.events.Topics;
import org.veriagent.contracts.model.Country;
import org.veriagent.core.audit.AuditService;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.bus.Events;
import org.veriagent.core.security.CurrentActor;
import org.veriagent.provider.mapping.MappingDocument;
import org.veriagent.provider.mapping.MappingProvider;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registro de versiones de mapeo. Ciclo: candidata (verificada por el kit) → aprobada por una persona → vigente.
 * Una versión bloqueada por el kit no se puede aprobar. Al activar se publica {@code platform.mapping.activated.v1}
 * y los adaptadores y conectores usan la nueva versión en el siguiente comprobante (recarga en caliente). La
 * reversión reactiva la versión vigente anterior. También es el {@link MappingProvider} de la plataforma.
 */
public class MappingRegistry implements MappingProvider {
    static final String PRODUCER = "mapping-registry";
    private static final Logger log = LoggerFactory.getLogger(MappingRegistry.class);

    public enum Status { CANDIDATE, BLOCKED, APPROVED, REJECTED, ACTIVE, SUPERSEDED }

    public record Version(long id, String provider, String country, int version, String profile, JsonNode body, String sha256,
                          Status status, boolean gatePassed, JsonNode verification, String source, String proposalRef,
                          String createdBy, Instant createdAt, String approvedBy, Instant approvedAt, String activatedBy,
                          Instant activatedAt, String decisionNote) {
        MappingDocument document() {
            return new MappingDocument(provider, country, version, profile, body, sha256);
        }
    }

    /** La operación no está permitida en el estado actual de la versión (HTTP 409). */
    public static class ConflictException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ConflictException(String message) {
            super(message);
        }
    }

    private final JdbcClient jdbc;
    private final MappingGate gate;
    private final EventBus bus;
    private final AuditService audit;
    private final Map<String, MappingDocument> cache = new ConcurrentHashMap<>();

    public MappingRegistry(JdbcClient jdbc, MappingGate gate, EventBus bus, AuditService audit) {
        this.jdbc = jdbc;
        this.gate = gate;
        this.bus = bus;
        this.audit = audit;
    }

    private static String key(String p, String c) { return p + "/" + c; }

    private static String norm(String provider) {
        String p = provider.toLowerCase(Locale.ROOT);
        if (!p.matches("[a-z]+")) throw new IllegalArgumentException("Proveedor inválido: " + provider);
        return p;
    }

    /** Siembra la línea base aprobada (v1) si el proveedor y el país aún no tienen versiones. */
    @Transactional
    public void seed(String provider, String country, JsonNode body) {
        if (!versions(provider, country).isEmpty()) return;
        var g = gate.verify(provider, Country.valueOf(country), body);
        long now = System.currentTimeMillis();
        insert(provider, country, 1, body, g.passed() ? Status.ACTIVE : Status.BLOCKED, g, "seed", null, "linea-base");
        if (g.passed()) {
            jdbc.sql("UPDATE mapping_version SET approved_by = 'linea-base', approved_at = :t, activated_by = 'linea-base', activated_at = :t "
                    + "WHERE provider = :p AND country = :c AND version = 1")
                .param("t", new Timestamp(now)).param("p", provider).param("c", country).update();
        } else {
            log.error("La línea base de {}/{} no pasa el kit: {}", provider, country, g.problem());
        }
        refresh(provider, country);
    }

    /** Registra una versión candidata y la somete al kit. Pueden proponer agentes, revisores y el sistema. */
    @Transactional
    public Version propose(String provider, String country, JsonNode body, String source, String proposalRef) {
        provider = norm(provider);
        Country c = Country.valueOf(country.toUpperCase(Locale.ROOT));
        var g = gate.verify(provider, c, body);
        int v = jdbc.sql("SELECT COALESCE(MAX(version), 0) + 1 FROM mapping_version WHERE provider = :p AND country = :c")
            .param("p", provider).param("c", c.name()).query(Integer.class).single();
        insert(provider, c.name(), v, body, g.passed() ? Status.CANDIDATE : Status.BLOCKED, g, source, proposalRef, CurrentActor.get().subject());
        audit.record("MAPPING_PROPOSED", "mapeo:" + provider + "/" + c + "/v" + v,
            (g.passed() ? "pasa el kit" : "bloqueado por el kit: " + g.findingsByRule()) + (proposalRef == null ? "" : " propuesta=" + proposalRef));
        return find(provider, c.name(), v);
    }

    /** Aprobación humana: solo si la versión pasó el kit (409 si no). La identidad sale del token. */
    @Transactional
    public Version approve(String provider, String country, int version, String note) {
        Version v = find(norm(provider), country, version);
        if (!v.gatePassed()) throw new ConflictException("No se puede aprobar: la versión " + version + " no pasó el kit de verificación");
        if (v.status() != Status.CANDIDATE) throw new ConflictException("La versión " + version + " está en estado " + v.status());
        decide(v, Status.APPROVED, "approved_by", "approved_at", note);
        audit.record("MAPPING_APPROVED", "mapeo:" + v.provider() + "/" + v.country() + "/v" + version, note);
        return find(v.provider(), v.country(), version);
    }

    @Transactional
    public Version reject(String provider, String country, int version, String note) {
        Version v = find(norm(provider), country, version);
        if (v.status() != Status.CANDIDATE && v.status() != Status.BLOCKED) {
            throw new ConflictException("La versión " + version + " está en estado " + v.status());
        }
        decide(v, Status.REJECTED, "approved_by", "approved_at", note);
        audit.record("MAPPING_REJECTED", "mapeo:" + v.provider() + "/" + v.country() + "/v" + version, note);
        return find(v.provider(), v.country(), version);
    }

    /** Activa una versión aprobada (o una vigente anterior, en una reversión) y avisa a los consumidores. */
    @Transactional
    public Version activate(String provider, String country, int version, String reason) {
        Version v = find(norm(provider), country, version);
        boolean rollback = v.status() == Status.SUPERSEDED && v.activatedAt() != null;
        if (!v.gatePassed() || !(v.status() == Status.APPROVED || rollback)) {
            throw new ConflictException("Solo se activa una versión aprobada que pasó el kit (o una vigente anterior); la versión "
                + version + " está en " + v.status());
        }
        jdbc.sql("UPDATE mapping_version SET status = 'SUPERSEDED' WHERE provider = :p AND country = :c AND status = 'ACTIVE'")
            .param("p", v.provider()).param("c", v.country()).update();
        String actor = CurrentActor.get().subject();
        jdbc.sql("UPDATE mapping_version SET status = 'ACTIVE', activated_by = :a, activated_at = :t, decision_note = COALESCE(:n, decision_note) "
                + "WHERE id = :id")
            .param("a", actor).param("t", Timestamp.from(Instant.now())).param("n", reason).param("id", v.id()).update();
        audit.record(rollback ? "MAPPING_ROLLBACK" : "MAPPING_ACTIVATED", "mapeo:" + v.provider() + "/" + v.country() + "/v" + version, reason);
        // Recarga y aviso tras el commit: si no, un consumidor podría leer la versión anterior y dejarla en caché.
        Runnable notify = () -> {
            refresh(v.provider(), v.country());
            bus.publish(Topics.MAPPING_ACTIVATED, Events.of(EventTypes.MAPPING_ACTIVATED, v.country(), null, PRODUCER,
                new Payloads.MappingActivated(v.provider(), v.country(), version, v.sha256(), actor, reason)));
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { notify.run(); }
            });
        } else {
            notify.run();
        }
        return find(v.provider(), v.country(), version);
    }

    /** Reversión: reactiva la versión que estuvo vigente justo antes de la actual. */
    @Transactional
    public Version rollback(String provider, String country, String reason) {
        String p = norm(provider);
        Version active = current(p, country).map(d -> find(p, country, d.version()))
            .orElseThrow(() -> new ConflictException("No hay versión vigente que revertir"));
        Version previous = jdbc.sql("""
                SELECT * FROM mapping_version WHERE provider = :p AND country = :c AND status = 'SUPERSEDED'
                  AND activated_at IS NOT NULL AND version <> :v ORDER BY activated_at DESC LIMIT 1""")
            .param("p", p).param("c", country).param("v", active.version()).query(ROW).optional()
            .orElseThrow(() -> new ConflictException("No hay una versión anterior a la que revertir"));
        return activate(p, country, previous.version(), reason == null ? "Reversión de la v" + active.version() : reason);
    }

    public Version find(String provider, String country, int version) {
        return jdbc.sql("SELECT * FROM mapping_version WHERE provider = :p AND country = :c AND version = :v")
            .param("p", provider).param("c", country.toUpperCase(Locale.ROOT)).param("v", version).query(ROW).optional()
            .orElseThrow(() -> new NoSuchElementException("No existe la versión " + version + " de " + provider + "/" + country));
    }

    public List<Version> list(String provider, String country) {
        return jdbc.sql("SELECT * FROM mapping_version WHERE provider = :p AND country = :c ORDER BY version")
            .param("p", provider).param("c", country.toUpperCase(Locale.ROOT)).query(ROW).list();
    }

    public List<Map<String, Object>> overview() {
        return jdbc.sql("""
            SELECT provider, country, COUNT(*) AS versions,
                   MAX(CASE WHEN status = 'ACTIVE' THEN version END) AS active_version
            FROM mapping_version GROUP BY provider, country ORDER BY provider, country""").query().listOfRows();
    }

    /** Recarga la versión vigente desde la base de datos (también al recibir el evento de activación). */
    public void refresh(String provider, String country) {
        jdbc.sql("SELECT * FROM mapping_version WHERE provider = :p AND country = :c AND status = 'ACTIVE'")
            .param("p", provider).param("c", country).query(ROW).optional()
            .ifPresentOrElse(v -> cache.put(key(provider, country), v.document()), () -> cache.remove(key(provider, country)));
    }

    public void refreshAll() {
        jdbc.sql("SELECT provider, country FROM mapping_version GROUP BY provider, country").query()
            .listOfRows().forEach(r -> refresh((String) r.get("provider"), (String) r.get("country")));
    }

    @Override
    public Optional<MappingDocument> current(String provider, String country) {
        return Optional.ofNullable(cache.get(key(provider, country)));
    }

    @Override
    public List<MappingDocument> versions(String provider, String country) {
        return list(provider, country).stream().map(Version::document).toList();
    }

    private void insert(String provider, String country, int version, JsonNode body, Status status, MappingGate.Result g,
                        String source, String proposalRef, String actor) {
        MappingDocument d = MappingDocument.of(provider, country, version, body);
        jdbc.sql("""
            INSERT INTO mapping_version (provider, country, version, profile, body, sha256, status, gate_passed, verification,
              source, proposal_ref, created_by, created_at)
            VALUES (:p, :c, :v, :profile, :body, :sha, :s, :g, :ver, :src, :ref, :by, :now)""")
            .param("p", provider).param("c", country).param("v", version).param("profile", d.profile())
            .param("body", ContractJson.mapper().writeValueAsString(body)).param("sha", d.sha256()).param("s", status.name())
            .param("g", g.passed()).param("ver", ContractJson.mapper().writeValueAsString(g)).param("src", source)
            .param("ref", proposalRef).param("by", actor).param("now", Timestamp.from(Instant.now())).update();
    }

    private void decide(Version v, Status to, String byCol, String atCol, String note) {
        jdbc.sql("UPDATE mapping_version SET status = :s, " + byCol + " = :a, " + atCol + " = :t, decision_note = :n WHERE id = :id")
            .param("s", to.name()).param("a", CurrentActor.get().subject()).param("t", Timestamp.from(Instant.now()))
            .param("n", note).param("id", v.id()).update();
    }

    private static Instant ts(java.sql.ResultSet rs, String c) throws java.sql.SQLException {
        Timestamp t = rs.getTimestamp(c);
        return t == null ? null : t.toInstant();
    }

    private static final RowMapper<Version> ROW = (rs, i) -> new Version(rs.getLong("id"), rs.getString("provider"),
        rs.getString("country"), rs.getInt("version"), rs.getString("profile"), ContractJson.mapper().readTree(rs.getString("body")),
        rs.getString("sha256"), Status.valueOf(rs.getString("status")), rs.getBoolean("gate_passed"),
        ContractJson.mapper().readTree(rs.getString("verification")), rs.getString("source"), rs.getString("proposal_ref"),
        rs.getString("created_by"), ts(rs, "created_at"), rs.getString("approved_by"), ts(rs, "approved_at"),
        rs.getString("activated_by"), ts(rs, "activated_at"), rs.getString("decision_note"));
}
