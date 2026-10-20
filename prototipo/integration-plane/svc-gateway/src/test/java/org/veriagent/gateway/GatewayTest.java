package org.veriagent.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.veriagent.core.security.DemoTokenStore;
import org.veriagent.core.testsupport.CoreTestApplication;
import org.veriagent.core.testsupport.TestTokens;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CoreTestApplication.class)
@AutoConfigureMockMvc
class GatewayTest {
    static final List<String> seen = new CopyOnWriteArrayList<>();
    static final HttpServer agents = start();

    static HttpServer start() {
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            s.createContext("/", ex -> {
                seen.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " " + ex.getRequestHeaders().getFirst("Authorization"));
                byte[] b = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
                ex.close();
            });
            s.start();
            return s;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("veriagent.gateway.agents-url", () -> "http://127.0.0.1:" + agents.getAddress().getPort());
    }

    @AfterAll
    static void stop() { agents.stop(0); }

    @Autowired MockMvc mvc;
    @Autowired DemoTokenStore store;
    TestTokens tokens;

    @BeforeEach
    void setUp() { tokens = new TestTokens(store); seen.clear(); }

    @Test
    @DisplayName("Sesión: identidad y rol del token; sin token 401")
    void session() throws Exception {
        mvc.perform(get("/api/session")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/session").header("Authorization", tokens.reviewer()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.subject").value("revisor.prueba")).andExpect(jsonPath("$.role").value("REVIEWER"));
    }

    @Test
    @DisplayName("Proxy: exige token, reenvía el mismo token y solo rutas de la lista blanca")
    void proxy() throws Exception {
        mvc.perform(get("/agents/api/proposals")).andExpect(status().isUnauthorized());
        assertEquals(List.of(), seen);
        String t = tokens.reviewer();
        mvc.perform(get("/agents/api/proposals").header("Authorization", t)).andExpect(status().isOk()).andExpect(content().json("{\"ok\":true}"));
        mvc.perform(post("/agents/api/proposals/prop-0a1b2c3d/decision").header("Authorization", t)
            .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECT\"}")).andExpect(status().isOk());
        assertEquals(List.of("GET /api/proposals " + t, "POST /api/proposals/prop-0a1b2c3d/decision " + t), seen);
        for (String bad : List.of("/agents/api/../admin", "/agents/api/proposals/x/decision", "/agents/api/docs", "/agents/api/proposals?x=1")) {
            // 404 de la lista blanca, o 400 del cortafuegos HTTP de Spring para rutas con "..": nunca se reenvía.
            mvc.perform(get(bad).header("Authorization", t)).andExpect(status().is4xxClientError());
        }
        assertEquals(2, seen.size());
    }

    @Test
    @DisplayName("El front se sirve sin token y con la política de seguridad de contenido")
    void front() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Security-Policy",
                org.hamcrest.Matchers.containsString("script-src 'self'")));
        mvc.perform(get("/app/main.js")).andExpect(status().isOk());
    }
}
