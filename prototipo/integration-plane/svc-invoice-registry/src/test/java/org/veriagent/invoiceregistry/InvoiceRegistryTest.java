package org.veriagent.invoiceregistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.events.Payloads;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.core.ledger.InvoiceLedger;
import org.veriagent.core.ledger.InvoiceLedger.Changes;
import org.veriagent.core.security.DemoTokenStore;
import org.veriagent.core.state.InvoiceState;
import org.veriagent.core.testsupport.CoreTestApplication;
import org.veriagent.core.testsupport.Fixtures;
import org.veriagent.core.testsupport.TestTokens;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = CoreTestApplication.class)
@AutoConfigureMockMvc
class InvoiceRegistryTest {
    @Autowired MockMvc mvc;
    @Autowired InvoiceLedger ledger;
    @Autowired DemoTokenStore store;

    @Test
    @DisplayName("Detalle con línea de tiempo y hallazgos; indicadores con balance de conservación; 401 sin token")
    void detailAndStats() throws Exception {
        InvoiceRequest inv = Fixtures.coInvoice(Fixtures.number());
        ledger.register(inv, Payloads.Channel.SYNC, ContractJson.mapper().writeValueAsString(inv));
        ledger.transition(inv.invoiceKey(), InvoiceState.RECEIVED, InvoiceState.FAILED, Changes.reason("Bloqueado por el kit")
            .withError("KIT_BLOCKED", "R-D1-01").withFindings("[{\"ruleId\":\"R-D1-01\",\"type\":\"D1\",\"location\":\"Invoice/InvoiceTypeCode\","
                + "\"message\":\"x\",\"source\":\"perfil-alfa-v2.1.md §3.2\",\"sourceSha256\":\"" + "a".repeat(64) + "\",\"ruleVersion\":\"2.1.0\"}]"));
        String t = new TestTokens(store).agent();
        mvc.perform(get("/api/invoices/" + inv.invoiceKey())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/invoices/" + inv.invoiceKey()).header("Authorization", t))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.timeline.length()").value(2))
            .andExpect(jsonPath("$.findings[0].source").value("perfil-alfa-v2.1.md §3.2"));
        mvc.perform(get("/api/invoices/stats").header("Authorization", t))
            .andExpect(status().isOk()).andExpect(jsonPath("$.conservation.balanced").value(true));
        mvc.perform(get("/api/invoices").param("status", "FAILED").header("Authorization", t))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("FAILED"));
        mvc.perform(get("/api/invoices/CO:NO-EXISTE").header("Authorization", t)).andExpect(status().isNotFound());
    }
}
