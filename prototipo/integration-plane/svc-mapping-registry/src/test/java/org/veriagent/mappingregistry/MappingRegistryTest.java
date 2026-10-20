package org.veriagent.mappingregistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.veriagent.contracts.ContractJson;
import org.veriagent.core.security.DemoTokenStore;
import org.veriagent.core.security.Role;
import org.veriagent.core.testsupport.CoreTestApplication;
import org.veriagent.core.testsupport.TestTokens;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CoreTestApplication.class)
@AutoConfigureMockMvc
class MappingRegistryTest {
    @Autowired MockMvc mvc;
    @Autowired MappingRegistry registry;
    @Autowired DemoTokenStore store;
    TestTokens tokens;

    @BeforeEach
    void setUp() { tokens = new TestTokens(store); }

    private String proposal(boolean broken) throws Exception {
        Path home = Path.of(System.getProperty("veriagent.home", "../.."));
        ObjectNode m = (ObjectNode) ContractJson.mapper().readTree(Files.readString(home.resolve("agentic-plane/golden/mapping-alfa-v2.json")));
        if (broken) m.put("profile", "perfil-inexistente");
        return "{\"mapping\":" + ContractJson.mapper().writeValueAsString(m) + ",\"source\":\"prueba\"}";
    }

    @Test
    @DisplayName("Separación de funciones: un revisor no puede aprobar la versión que él mismo propuso (409)")
    void proposerCannotApprove() throws Exception {
        String rev = tokens.bearer("revisor.autor", Role.REVIEWER);
        String body = mvc.perform(post("/api/mappings/alfa/CO/candidates").header("Authorization", rev)
                .contentType(MediaType.APPLICATION_JSON).content(proposal(false)))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int v = ContractJson.mapper().readTree(body).get("version").asInt();
        mvc.perform(post("/api/mappings/alfa/CO/versions/" + v + "/approve").header("Authorization", rev))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/mappings/alfa/CO/versions/" + v + "/approve").header("Authorization", tokens.bearer("revisora.otra", Role.REVIEWER)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.approvedBy").value("revisora.otra"));
    }

    @Test
    @DisplayName("Línea base sembrada y vigente para alfa/CO y beta/PE")
    void seeded() {
        assertTrue(registry.current("alfa", "CO").isPresent());
        assertTrue(registry.current("beta", "PE").isPresent());
    }

    @Test
    @DisplayName("Ciclo: el agente propone; bloqueada no se aprueba (409); el agente no aprueba (403); revisor aprueba, activa y revierte")
    void lifecycle() throws Exception {
        String bad = mvc.perform(post("/api/mappings/alfa/CO/candidates").header("Authorization", tokens.agent())
                .contentType(MediaType.APPLICATION_JSON).content(proposal(true)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("BLOCKED"))
            .andReturn().getResponse().getContentAsString();
        int badV = ContractJson.mapper().readTree(bad).get("version").asInt();
        mvc.perform(post("/api/mappings/alfa/CO/versions/" + badV + "/approve").header("Authorization", tokens.reviewer()))
            .andExpect(status().isConflict());

        String ok = mvc.perform(post("/api/mappings/alfa/CO/candidates").header("Authorization", tokens.agent())
                .contentType(MediaType.APPLICATION_JSON).content(proposal(false)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CANDIDATE"))
            .andReturn().getResponse().getContentAsString();
        int v = ContractJson.mapper().readTree(ok).get("version").asInt();
        String base = "/api/mappings/alfa/CO/versions/" + v;
        mvc.perform(post(base + "/approve")).andExpect(status().isUnauthorized());
        mvc.perform(post(base + "/approve").header("Authorization", tokens.agent())).andExpect(status().isForbidden());
        String rev = tokens.bearer("revisora.real", Role.REVIEWER);
        mvc.perform(post(base + "/approve").header("Authorization", rev).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewer\":\"otra.persona\"}")).andExpect(status().isForbidden());
        mvc.perform(post(base + "/approve").header("Authorization", rev).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"ok\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.approvedBy").value("revisora.real"));
        mvc.perform(post(base + "/activate").header("Authorization", tokens.agent())).andExpect(status().isForbidden());
        mvc.perform(post(base + "/activate").header("Authorization", rev)).andExpect(status().isOk())
            .andExpect(jsonPath("$.activatedBy").value("revisora.real"));
        assertEquals(v, registry.current("alfa", "CO").orElseThrow().version());
        mvc.perform(post("/api/mappings/alfa/CO/rollback").header("Authorization", rev)).andExpect(status().isOk());
        assertEquals(1, registry.current("alfa", "CO").orElseThrow().version());
    }

    @Test
    @DisplayName("Verificación en seco: el agente obtiene el resultado del kit sin registrar ninguna versión; el operador no puede")
    void dryRunCheck() throws Exception {
        int before = registry.list("alfa", "CO").size();
        mvc.perform(post("/api/mappings/alfa/CO/check").header("Authorization", tokens.agent())
                .contentType(MediaType.APPLICATION_JSON).content(proposal(false)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(true)).andExpect(jsonPath("$.runtimeDocuments").value(50));
        mvc.perform(post("/api/mappings/alfa/CO/check").header("Authorization", tokens.agent())
                .contentType(MediaType.APPLICATION_JSON).content(proposal(true)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(false));
        mvc.perform(post("/api/mappings/alfa/CO/check").header("Authorization", tokens.operator())
                .contentType(MediaType.APPLICATION_JSON).content(proposal(false)))
            .andExpect(status().isForbidden());
        assertEquals(before, registry.list("alfa", "CO").size());
    }
}
