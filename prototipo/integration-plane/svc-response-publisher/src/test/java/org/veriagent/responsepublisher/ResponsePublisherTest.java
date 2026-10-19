package org.veriagent.responsepublisher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.events.Payloads;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.bus.FlowEvents;
import org.veriagent.core.ledger.InvoiceLedger;
import org.veriagent.core.testsupport.CoreTestApplication;
import org.veriagent.core.testsupport.Fixtures;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = CoreTestApplication.class,
    properties = "veriagent.providers.sftp-root=${java.io.tmpdir}/veriagent-test/pub-${random.uuid}")
class ResponsePublisherTest {
    @Autowired ResponsePublisher publisher;
    @Autowired FlowEvents flow;
    @Autowired EventBus bus;
    @Autowired InvoiceLedger ledger;
    @Autowired JdbcClient jdbc;

    @Test
    @DisplayName("Lote: una línea JSON por comprobante en el archivo de respuestas del lote de origen; síncrono: entrega registrada")
    void deliversToOrigin() throws Exception {
        InvoiceRequest a = Fixtures.coInvoice(Fixtures.number());
        InvoiceRequest b = Fixtures.coInvoice(Fixtures.number());
        ledger.register(a, Payloads.Channel.BATCH, ContractJson.mapper().writeValueAsString(a), "lote-origen.json");
        ledger.register(b, Payloads.Channel.SYNC, ContractJson.mapper().writeValueAsString(b));
        flow.response(a.invoiceKey(), "CO", "prueba", Payloads.FinalStatus.AUTHORIZED, Payloads.Channel.BATCH, "alfa", "OK", "Autorizado", "AUT-1", null);
        flow.response(b.invoiceKey(), "CO", "prueba", Payloads.FinalStatus.FAILED, Payloads.Channel.SYNC, "alfa", "KIT_BLOCKED", "Bloqueado", null, null);
        assertTrue(bus.awaitQuiescence(Duration.ofSeconds(5)));
        Path file = publisher.outbox().resolve("co").resolve("lote-origen.json.respuestas.jsonl");
        List<String> lines = Files.readAllLines(file);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains(a.invoiceKey()) && lines.get(0).contains("AUT-1"));
        var rows = jdbc.sql("SELECT channel, target FROM response_delivery WHERE invoice_key IN (:a, :b) ORDER BY channel")
            .param("a", a.invoiceKey()).param("b", b.invoiceKey()).query().listOfRows();
        assertEquals(2, rows.size());
        assertEquals("respuesta HTTP de la fachada", rows.get(1).get("target"));
    }
}
