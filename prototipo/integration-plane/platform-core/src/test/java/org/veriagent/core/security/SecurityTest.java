package org.veriagent.core.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.veriagent.core.VeriagentProperties;
import org.veriagent.core.audit.AuditService;
import org.veriagent.core.testsupport.CoreTestApplication;
import org.veriagent.core.testsupport.TestTokens;
import org.veriagent.contracts.ContractJson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Seguridad compartida: denegar por defecto, roles, identidad del token y anti-suplantación. */
@SpringBootTest(classes = CoreTestApplication.class)
@AutoConfigureMockMvc
class SecurityTest {
    @Autowired MockMvc mvc;
    @Autowired DemoTokenStore store;
    @Autowired AuditService audit;
    @Autowired VeriagentProperties props;
    TestTokens tokens;

    @BeforeEach
    void setUp() { tokens = new TestTokens(store); }

    private static String decision(String reviewer) {
        return reviewer == null ? "{\"decision\":\"APPROVE\"}" : "{\"decision\":\"APPROVE\",\"reviewer\":\"" + reviewer + "\"}";
    }

    @Test
    @DisplayName("Sin token: 401, también en lecturas (denegar por defecto)")
    void noTokenIs401() throws Exception {
        mvc.perform(post("/api/probe/decision").contentType(MediaType.APPLICATION_JSON).content(decision(null)))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/probe/ping")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/probe/ping").header("Authorization", "Bearer token-inventado-de-prueba-123"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("La salud es pública")
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Un token de agente recibe 403 en una decisión; tampoco operador ni sistema")
    void agentCannotDecide() throws Exception {
        for (String t : List.of(tokens.agent(), tokens.operator(), tokens.system())) {
            mvc.perform(post("/api/probe/decision").header("Authorization", t)
                    .contentType(MediaType.APPLICATION_JSON).content(decision(null)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    @DisplayName("La identidad registrada en la auditoría es la del token")
    void identityComesFromToken() throws Exception {
        String t = tokens.bearer("revisora.auditada", Role.REVIEWER);
        int before = audit.forTarget("probe-1").size();
        mvc.perform(post("/api/probe/decision").header("Authorization", t)
                .contentType(MediaType.APPLICATION_JSON).content(decision(null)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.decidedBy").value("revisora.auditada"));
        var entries = audit.forTarget("probe-1");
        assertEquals(before + 1, entries.size());
        assertEquals("revisora.auditada", entries.get(entries.size() - 1).actor());
        assertEquals("REVIEWER", entries.get(entries.size() - 1).actorRole());
    }

    @Test
    @DisplayName("Suplantación en el cuerpo: 403 y sin efecto en la auditoría; si coincide, se acepta")
    void impersonationHasNoEffect() throws Exception {
        String t = tokens.bearer("revisor.honesto", Role.REVIEWER);
        int before = audit.forTarget("probe-1").size();
        mvc.perform(post("/api/probe/decision").header("Authorization", t)
                .contentType(MediaType.APPLICATION_JSON).content(decision("lider.tecnico")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IMPERSONATION"));
        assertEquals(before, audit.forTarget("probe-1").size());
        mvc.perform(post("/api/probe/decision").header("Authorization", t)
                .contentType(MediaType.APPLICATION_JSON).content(decision("revisor.honesto")))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Una cabecera editable no cambia la identidad")
    void headersDoNotChangeIdentity() throws Exception {
        mvc.perform(post("/api/probe/decision").header("Authorization", tokens.bearer("revisor.real", Role.REVIEWER))
                .header("X-User", "otra.persona").header("X-Reviewer", "otra.persona")
                .contentType(MediaType.APPLICATION_JSON).content(decision(null)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.decidedBy").value("revisor.real"));
    }

    @Test
    @DisplayName("Los tokens son aleatorios, se escriben en el archivo local y no se repiten entre arranques")
    void tokensAreRandomAndWrittenToFile() throws Exception {
        Path file = Path.of(props.getSecurity().getTokensFile());
        assertTrue(Files.exists(file), file.toString());
        var root = ContractJson.mapper().readTree(Files.readString(file));
        Set<String> roles = new HashSet<>();
        Set<String> reviewers = new HashSet<>();
        for (var n : root.get("tokens")) {
            roles.add(n.get("role").asString());
            if ("REVIEWER".equals(n.get("role").asString())) reviewers.add(n.get("subject").asString());
            assertTrue(n.get("token").asString().length() >= 40);
            assertTrue(store.resolve(n.get("token").asString()).isPresent());
        }
        assertEquals(Set.of("REVIEWER", "AGENT", "OPERATOR", "SYSTEM"), roles);
        // Dos revisores distintos: quien propone una versión de mapeo no puede ser quien la aprueba (H-05).
        assertEquals(Set.of("reviewer-a", "reviewer-b"), reviewers);
        Path other = Files.createTempFile("tokens", ".json");
        DemoTokenStore.generate(other, DemoTokenStore.DEFAULT_IDENTITIES);
        String a = Files.readString(file), b = Files.readString(other);
        var tokensA = new HashSet<String>();
        ContractJson.mapper().readTree(a).get("tokens").forEach(n -> tokensA.add(n.get("token").asString()));
        ContractJson.mapper().readTree(b).get("tokens").forEach(n -> assertFalse(tokensA.contains(n.get("token").asString())));
        Files.deleteIfExists(other);
    }

    @Test
    @DisplayName("CORS: el origen del front se acepta y cualquier otro se rechaza")
    void corsIsRestricted() throws Exception {
        mvc.perform(options("/api/probe/ping").header("Origin", "http://127.0.0.1:8080")
                .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isOk());
        mvc.perform(options("/api/probe/ping").header("Origin", "http://sitio-ajeno.example")
                .header("Access-Control-Request-Method", "GET"))
            .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("La correlación se genera, se respeta o se sustituye si no es segura")
    void correlationHeader() throws Exception {
        String t = tokens.operator();
        var r1 = mvc.perform(get("/api/probe/ping").header("Authorization", t)).andReturn().getResponse();
        assertNotNull(r1.getHeader("X-Correlation-Id"));
        var r2 = mvc.perform(get("/api/probe/ping").header("Authorization", t).header("X-Correlation-Id", "corr-abc-1"))
            .andReturn().getResponse();
        assertEquals("corr-abc-1", r2.getHeader("X-Correlation-Id"));
        assertTrue(r2.getContentAsString().contains("corr-abc-1"));
        var r3 = mvc.perform(get("/api/probe/ping").header("Authorization", t).header("X-Correlation-Id", "<script>x</script>"))
            .andReturn().getResponse();
        assertNotEquals("<script>x</script>", r3.getHeader("X-Correlation-Id"));
    }
}
