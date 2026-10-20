package org.veriagent.mappingregistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.veriagent.contracts.ContractJson;
import org.veriagent.core.security.DemoTokenStore;
import org.veriagent.core.security.Role;
import org.veriagent.core.testsupport.CoreTestApplication;
import org.veriagent.core.testsupport.TestTokens;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CoreTestApplication.class)
@AutoConfigureMockMvc
class ReviewAuditTest {
    @Autowired MockMvc mvc;
    @Autowired DemoTokenStore store;
    @Autowired JdbcClient jdbc;
    TestTokens tokens;

    @BeforeEach
    void setUp() { tokens = new TestTokens(store); }

    private String mapping() throws Exception {
        Path home = Path.of(System.getProperty("veriagent.home", "../.."));
        JsonNode m = ContractJson.mapper().readTree(Files.readString(home.resolve("agentic-plane/golden/mapping-alfa-v2.json")));
        return "{\"mapping\":" + ContractJson.mapper().writeValueAsString(m) + ",\"source\":\"prueba\"}";
    }

    /** El agente propone y la persona indicada aprueba. Devuelve el número de versión. */
    private int approvedVersion(String approver) throws Exception {
        String body = mvc.perform(post("/api/mappings/alfa/CO/candidates").header("Authorization", tokens.agent())
                .contentType(MediaType.APPLICATION_JSON).content(mapping()))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int v = ContractJson.mapper().readTree(body).get("version").asInt();
        mvc.perform(post("/api/mappings/alfa/CO/versions/" + v + "/approve").header("Authorization", tokens.bearer(approver, Role.REVIEWER)))
            .andExpect(status().isOk());
        return v;
    }

    private JsonNode sample(String auth, String body) throws Exception {
        return ContractJson.mapper().readTree(mvc.perform(post("/api/review-audits/sample").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    @Test
    @DisplayName("Selección reproducible: misma semilla y misma población, misma muestra; la tasa se respeta")
    void selectionIsReproducible() {
        List<Long> ids = LongStream.rangeClosed(1, 10_000).boxed().toList();
        List<Long> a = ReviewAudit.select(ids, 0.10, 2026L).stream().map(p -> (long) p[0]).toList();
        List<Long> b = ReviewAudit.select(new ArrayList<>(ids.reversed()), 0.10, 2026L).stream().map(p -> (long) p[0]).toList();
        List<Long> c = ReviewAudit.select(ids, 0.10, 2027L).stream().map(p -> (long) p[0]).toList();
        assertEquals(a, b, "el orden de llegada de la población no cambia la muestra");
        assertNotEquals(a, c, "otra semilla, otra muestra");
        assertTrue(a.size() > 900 && a.size() < 1100, "tasa 10 % sobre 10.000: " + a.size());
        assertEquals(ids, ReviewAudit.select(ids, 1.0, 1L).stream().map(p -> (long) p[0]).toList());
    }

    @Test
    @DisplayName("Control de rol: sin token 401; agente y revisor no muestrean (403); el agente no consulta ni revisa (403)")
    void roles() throws Exception {
        mvc.perform(post("/api/review-audits/sample").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/review-audits/sample").header("Authorization", tokens.agent())
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/review-audits/sample").header("Authorization", tokens.reviewer())
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/review-audits").header("Authorization", tokens.agent())).andExpect(status().isForbidden());
        mvc.perform(post("/api/review-audits/1/decision").header("Authorization", tokens.agent())
            .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"CONFIRMED\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/review-audits/sample").header("Authorization", tokens.operator())
            .contentType(MediaType.APPLICATION_JSON).content("{\"rate\":1.5}")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Muestreo y segunda revisión: semilla registrada, cola, distinta persona de quien aprobó, authorization en audit_log")
    void sampleAndSecondReview() throws Exception {
        int v1 = approvedVersion("revisora.a");
        int v2 = approvedVersion("revisor.b");

        JsonNode s = sample(tokens.operator(), "{\"rate\":1.0,\"seed\":4242}");
        assertEquals(4242L, s.get("seed").asLong());
        assertEquals(1.0, s.get("rate").asDouble());
        List<Integer> versions = new ArrayList<>();
        s.get("selected").forEach(i -> versions.add(i.get("version").asInt()));
        assertTrue(versions.containsAll(List.of(v1, v2)), versions::toString);
        s.get("selected").forEach(i -> {
            assertEquals("PENDING", i.get("status").asString());
            assertEquals(4242L, i.get("sampleSeed").asLong());
            assertEquals("operador.prueba", i.get("sampledBy").asString());
        });
        assertFalse(versions.contains(1), "la línea base sembrada no es una decisión humana y no entra en la muestra");
        // Una decisión ya muestreada no vuelve a entrar.
        assertEquals(0, sample(tokens.system(), "{\"rate\":1.0,\"seed\":1}").get("selected").size());

        long auditId = -1;
        for (JsonNode i : s.get("selected")) if (i.get("version").asInt() == v1) auditId = i.get("id").asLong();
        mvc.perform(get("/api/review-audits?status=PENDING").header("Authorization", tokens.reviewer()))
            .andExpect(status().isOk());

        String url = "/api/review-audits/" + auditId + "/decision";
        // Quien aprobó no puede hacer la segunda revisión.
        mvc.perform(post(url).header("Authorization", tokens.bearer("revisora.a", Role.REVIEWER))
            .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"CONFIRMED\"}")).andExpect(status().isConflict());
        // Suplantación en el cuerpo: rechazada.
        mvc.perform(post(url).header("Authorization", tokens.bearer("revisor.b", Role.REVIEWER))
            .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"CONFIRMED\",\"reviewer\":\"revisora.c\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post(url).header("Authorization", tokens.bearer("revisor.b", Role.REVIEWER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"DISPUTED\",\"note\":\"falta citar la sección\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISPUTED"))
            .andExpect(jsonPath("$.secondReviewer").value("revisor.b")).andExpect(jsonPath("$.approvedBy").value("revisora.a"));
        mvc.perform(post(url).header("Authorization", tokens.bearer("revisora.c", Role.REVIEWER))
            .contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"CONFIRMED\"}")).andExpect(status().isConflict());

        String sampled = jdbc.sql("SELECT details FROM audit_log WHERE action = 'REVIEW_AUDIT_SAMPLED' AND target = :t")
            .param("t", "auditoria:" + s.get("sampleId").asString()).query(String.class).single();
        assertTrue(sampled.contains("semilla=4242") && sampled.contains("tasa=1.0"), sampled);
        String actor = jdbc.sql("SELECT actor FROM audit_log WHERE action = 'REVIEW_AUDIT_DISPUTED' AND target = :t")
            .param("t", "mapeo:alfa/CO/v" + v1).query(String.class).single();
        assertEquals("revisor.b", actor, "la identidad registrada es la del token");
    }
}
