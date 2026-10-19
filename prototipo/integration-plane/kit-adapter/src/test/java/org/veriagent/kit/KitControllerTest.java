package org.veriagent.kit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.veriagent.core.VeriagentProperties;
import org.veriagent.core.security.DemoTokenStore;
import org.veriagent.core.testsupport.CoreTestApplication;
import org.veriagent.core.testsupport.TestTokens;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.hasLength;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CoreTestApplication.class)
@AutoConfigureMockMvc
class KitControllerTest {
    @Autowired MockMvc mvc;
    @Autowired DemoTokenStore store;
    @Autowired VeriagentProperties props;
    TestTokens tokens;
    String valid;
    String beta;

    @BeforeEach
    void setUp() throws Exception {
        tokens = new TestTokens(store);
        Path res = Path.of(props.getHome()).resolve("verification-kit/src/test/resources");
        valid = Files.readString(res.resolve("factura-valida.xml"));
        beta = Files.readString(res.resolve("beta-valido.txt"));
    }

    @Test
    @DisplayName("Un comprobante válido devuelve 200 sin hallazgos y con la huella de la especificación")
    void validDocument() throws Exception {
        mvc.perform(post("/api/kit/verify").param("profile", "alfa").param("mode", "RUNTIME")
                .header("Authorization", tokens.agent()).contentType(MediaType.APPLICATION_XML).content(valid))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.passed").value(true))
            .andExpect(jsonPath("$.findings.length()").value(0))
            .andExpect(jsonPath("$.specSha256", hasLength(64)));
    }

    @Test
    @DisplayName("Un código de catálogo inexistente devuelve 422 con regla, tipo D1, sección y huella")
    void defectiveDocument() throws Exception {
        String bad = valid.replace("<InvoiceTypeCode>01</InvoiceTypeCode>", "<InvoiceTypeCode>07</InvoiceTypeCode>");
        mvc.perform(post("/api/kit/verify").header("Authorization", tokens.operator()).content(bad))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.passed").value(false))
            .andExpect(jsonPath("$.findings[0].ruleId").value("R-D1-01"))
            .andExpect(jsonPath("$.findings[0].type").value("D1"))
            .andExpect(jsonPath("$.findings[0].source").value("perfil-alfa-v2.1.md §3.2"))
            .andExpect(jsonPath("$.findings[0].sourceSha256", hasLength(64)));
    }

    @Test
    @DisplayName("El formato Beta se verifica con su propio perfil")
    void betaProfile() throws Exception {
        mvc.perform(post("/api/kit/verify").param("profile", "beta").header("Authorization", tokens.operator()).content(beta))
            .andExpect(status().isOk());
        String shifted = beta.replaceFirst("\\|([^|\\n]*)\\n", "|$1|extra\n");
        mvc.perform(post("/api/kit/verify").param("profile", "beta").header("Authorization", tokens.operator()).content(shifted))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Entradas hostiles: DOCTYPE rechazado, documento de más de 2 MB rechazado, perfil desconocido 400")
    void hostileInputs() throws Exception {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
            + valid.substring(valid.indexOf("<Invoice"));
        mvc.perform(post("/api/kit/verify").header("Authorization", tokens.operator()).content(xxe))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.findings[0].ruleId").value("R-E0-03"));
        mvc.perform(post("/api/kit/verify").header("Authorization", tokens.operator()).content("x".repeat(KitController.MAX_BODY + 10)))
            .andExpect(status().isPayloadTooLarge());
        mvc.perform(post("/api/kit/verify").param("profile", "desconocido").header("Authorization", tokens.operator()).content(valid))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Sin token: 401; la lista de perfiles exige token")
    void requiresToken() throws Exception {
        mvc.perform(post("/api/kit/verify").content(valid)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/kit/profiles")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/kit/profiles").header("Authorization", tokens.agent()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4));
    }
}
