package org.veriagent.invoicerouter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.veriagent.contracts.events.EventTypes;
import org.veriagent.contracts.events.Payloads;
import org.veriagent.contracts.events.Topics;
import org.veriagent.contracts.model.Country;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.bus.Events;
import org.veriagent.core.security.DemoTokenStore;
import org.veriagent.core.testsupport.CoreTestApplication;
import org.veriagent.core.testsupport.Fixtures;
import org.veriagent.core.testsupport.TestTokens;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CoreTestApplication.class)
@AutoConfigureMockMvc
class InvoiceRouterTest {
    @Autowired EventBus bus;
    @Autowired MockMvc mvc;
    @Autowired DemoTokenStore store;

    private void request(InvoiceRequest inv) {
        bus.publish(Topics.invoiceRequest(inv.country()), Events.of(EventTypes.INVOICE_REQUESTED, inv.country().name(),
            inv.invoiceKey(), "prueba", new Payloads.InvoiceRequested(Payloads.Channel.BATCH, "lote.json", inv)));
    }

    @Test
    @DisplayName("Enruta CO a alfa en co.alfa.request.v1 con la regla y la versión de la tabla")
    void routesByCountry() {
        List<String> topics = new CopyOnWriteArrayList<>();
        List<Payloads.ProviderRequested> got = new CopyOnWriteArrayList<>();
        try (var s = bus.subscribe(Topics.ALL_PROVIDER_REQUESTS, "prueba-router", (t, e) -> {
            topics.add(t);
            got.add(Events.payload(e, Payloads.ProviderRequested.class));
        })) {
            InvoiceRequest inv = Fixtures.coInvoice(Fixtures.number());
            request(inv);
            assertTrue(bus.awaitQuiescence(Duration.ofSeconds(5)));
            assertEquals(List.of("co.alfa.request.v1"), topics);
            assertEquals("alfa", got.get(0).provider());
            assertEquals("R1@v1", got.get(0).routingRule());
            assertEquals(inv.invoiceKey(), got.get(0).invoice().invoiceKey());
        }
    }

    @Test
    @DisplayName("La tabla vigente se consulta con token y sin él responde 401")
    void tableApi() throws Exception {
        mvc.perform(get("/api/router/table")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/router/table").header("Authorization", new TestTokens(store).agent()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.rules.length()").value(3));
        assertEquals(Country.CO, Topics.countryOf("co.alfa.request.v1"));
    }
}
