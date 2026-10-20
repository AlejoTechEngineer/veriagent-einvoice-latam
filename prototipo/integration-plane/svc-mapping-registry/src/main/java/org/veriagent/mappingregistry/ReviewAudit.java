package org.veriagent.mappingregistry;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.veriagent.core.audit.AuditService;
import org.veriagent.core.security.CurrentActor;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.UUID;

/**
 * Auditoría por muestreo contra el sesgo de automatización (A-10, riesgo R6). Sortea, con una tasa configurable y una
 * semilla que queda registrada, decisiones de revisión ya aprobadas por una persona y las pone en una cola de segunda
 * revisión. La segunda revisión la hace una persona distinta de quien aprobó (409 si es la misma). Cada muestreo y
 * cada segunda revisión quedan en {@code audit_log} con la identidad del token.
 */
public class ReviewAudit {
    /** Identidad con la que se siembra la línea base: no es una decisión humana y no entra en el muestreo. */
    static final String BASELINE = "linea-base";

    public enum Status { PENDING, CONFIRMED, DISPUTED }

    public record Item(long id, long mappingVersionId, String provider, String country, int version, String approvedBy,
                       Instant approvedAt, String sampleId, double sampleRate, long sampleSeed, double draw,
                       String sampledBy, Instant sampledAt, Status status, String secondReviewer, String outcomeNote,
                       Instant decidedAt) {}

    public record Sample(String sampleId, double rate, long seed, int population, List<Item> selected) {}

    private record Candidate(long id, String provider, String country, int version, String approvedBy, Instant approvedAt) {}

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final double defaultRate;
    private final Long configuredSeed;

    public ReviewAudit(JdbcClient jdbc, AuditService audit, double defaultRate, Long configuredSeed) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.defaultRate = checkRate(defaultRate);
        this.configuredSeed = configuredSeed;
    }

    private static double checkRate(double rate) {
        if (!(rate > 0.0 && rate <= 1.0)) throw new IllegalArgumentException("La tasa de muestreo debe estar en (0, 1]: " + rate);
        return rate;
    }

    /**
     * Selección reproducible: la población se ordena por id y cada decisión recibe un valor de {@code new Random(seed)};
     * entra en la muestra si el valor es menor que la tasa. Misma semilla y misma población, misma selección.
     */
    static List<double[]> select(List<Long> populationIds, double rate, long seed) {
        Random rnd = new Random(seed);
        List<double[]> out = new ArrayList<>();
        for (long id : populationIds.stream().sorted().toList()) {
            double draw = rnd.nextDouble();
            if (draw < rate) out.add(new double[] {id, draw});
        }
        return out;
    }

    @Transactional
    public Sample sample(Double rateOrNull, Long seedOrNull) {
        double rate = checkRate(rateOrNull == null ? defaultRate : rateOrNull);
        long seed = seedOrNull != null ? seedOrNull : configuredSeed != null ? configuredSeed : new SecureRandom().nextLong();
        List<Candidate> population = jdbc.sql("""
                SELECT id, provider, country, version, approved_by, approved_at FROM mapping_version
                WHERE status IN ('APPROVED', 'ACTIVE', 'SUPERSEDED') AND approved_by IS NOT NULL AND approved_by <> :base
                  AND id NOT IN (SELECT mapping_version_id FROM review_audit)
                ORDER BY id""")
            .param("base", BASELINE)
            .query((rs, i) -> new Candidate(rs.getLong("id"), rs.getString("provider"), rs.getString("country"),
                rs.getInt("version"), rs.getString("approved_by"), ts(rs.getTimestamp("approved_at"))))
            .list();
        String sampleId = "muestra-" + UUID.randomUUID().toString().substring(0, 8);
        String actor = CurrentActor.get().subject();
        Instant now = Instant.now();
        List<Item> selected = new ArrayList<>();
        for (double[] pick : select(population.stream().map(Candidate::id).toList(), rate, seed)) {
            Candidate c = population.stream().filter(x -> x.id() == (long) pick[0]).findFirst().orElseThrow();
            jdbc.sql("""
                    INSERT INTO review_audit (mapping_version_id, provider, country, version, approved_by, approved_at,
                      sample_id, sample_rate, sample_seed, draw, sampled_by, sampled_at, status)
                    VALUES (:mv, :p, :c, :v, :ab, :aat, :sid, :rate, :seed, :draw, :by, :now, 'PENDING')""")
                .param("mv", c.id()).param("p", c.provider()).param("c", c.country()).param("v", c.version())
                .param("ab", c.approvedBy()).param("aat", c.approvedAt() == null ? null : Timestamp.from(c.approvedAt()))
                .param("sid", sampleId).param("rate", rate).param("seed", seed).param("draw", pick[1])
                .param("by", actor).param("now", Timestamp.from(now))
                .update();
            selected.add(find(lastId(c.id())));
        }
        audit.record("REVIEW_AUDIT_SAMPLED", "auditoria:" + sampleId, String.format(Locale.ROOT,
            "tasa=%s semilla=%d poblacion=%d seleccionadas=%d versiones=%s", rate, seed, population.size(), selected.size(),
            selected.stream().map(i -> i.provider() + "/" + i.country() + "/v" + i.version()).toList()));
        return new Sample(sampleId, rate, seed, population.size(), selected);
    }

    private long lastId(long mappingVersionId) {
        return jdbc.sql("SELECT id FROM review_audit WHERE mapping_version_id = :mv").param("mv", mappingVersionId)
            .query(Long.class).single();
    }

    /** Segunda revisión: solo una persona distinta de quien aprobó la decisión original. */
    @Transactional
    public Item decide(long id, Status outcome, String note) {
        if (outcome == null || outcome == Status.PENDING) throw new IllegalArgumentException("El resultado debe ser CONFIRMED o DISPUTED");
        Item item = find(id);
        if (item.status() != Status.PENDING) {
            throw new MappingRegistry.ConflictException("La auditoría " + id + " ya tiene segunda revisión (" + item.status() + ")");
        }
        String reviewer = CurrentActor.get().subject();
        if (reviewer.equals(item.approvedBy())) {
            throw new MappingRegistry.ConflictException(
                "Quien aprobó la versión no puede hacer su segunda revisión: debe hacerla otra persona revisora");
        }
        jdbc.sql("""
                UPDATE review_audit SET status = :s, second_reviewer = :r, outcome_note = :n, decided_at = :t
                WHERE id = :id AND status = 'PENDING'""")
            .param("s", outcome.name()).param("r", reviewer).param("n", note).param("t", Timestamp.from(Instant.now()))
            .param("id", id).update();
        audit.record("REVIEW_AUDIT_" + outcome.name(), "mapeo:" + item.provider() + "/" + item.country() + "/v" + item.version(),
            "auditoria=" + id + " muestra=" + item.sampleId() + " aprobada_por=" + item.approvedBy()
                + (note == null ? "" : " nota=" + note));
        return find(id);
    }

    public Item find(long id) {
        return jdbc.sql("SELECT * FROM review_audit WHERE id = :id").param("id", id).query(ROW).optional()
            .orElseThrow(() -> new NoSuchElementException("No existe la auditoría " + id));
    }

    public List<Item> list(Status status, int limit) {
        int n = Math.max(1, Math.min(limit, 500));
        if (status == null) {
            return jdbc.sql("SELECT * FROM review_audit ORDER BY id DESC LIMIT :n").param("n", n).query(ROW).list();
        }
        return jdbc.sql("SELECT * FROM review_audit WHERE status = :s ORDER BY id LIMIT :n")
            .param("s", status.name()).param("n", n).query(ROW).list();
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static final RowMapper<Item> ROW = (rs, i) -> new Item(rs.getLong("id"), rs.getLong("mapping_version_id"),
        rs.getString("provider"), rs.getString("country"), rs.getInt("version"), rs.getString("approved_by"),
        ts(rs.getTimestamp("approved_at")), rs.getString("sample_id"), rs.getDouble("sample_rate"), rs.getLong("sample_seed"),
        rs.getDouble("draw"), rs.getString("sampled_by"), ts(rs.getTimestamp("sampled_at")), Status.valueOf(rs.getString("status")),
        rs.getString("second_reviewer"), rs.getString("outcome_note"), ts(rs.getTimestamp("decided_at")));
}
