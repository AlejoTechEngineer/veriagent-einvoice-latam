package org.veriagent.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.veriagent.simulators.SimulatorController;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Las rutas /sim/ solo existen si los simuladores se habilitan explícitamente; aunque alguien declare rutas
 * públicas de simulador, con los simuladores desactivados siguen exigiendo token.
 */
@SpringBootTest(properties = {
    "veriagent.ingest.poller-enabled=false",
    "veriagent.services.provider-simulators=false",
    "veriagent.security.simulator-public-paths=/sim/*/api/v1/**"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SimulatorExposureTest {
    @Autowired ApplicationContext context;
    @Autowired MockMvc mvc;

    @Test
    @DisplayName("Con los simuladores desactivados no hay rutas /sim/ y no se abre ninguna ruta sin token")
    void simulatorsOffByDefault() throws Exception {
        assertTrue(context.getBeansOfType(SimulatorController.class).isEmpty());
        mvc.perform(post("/sim/alfa/api/v1/documents").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
    }
}
