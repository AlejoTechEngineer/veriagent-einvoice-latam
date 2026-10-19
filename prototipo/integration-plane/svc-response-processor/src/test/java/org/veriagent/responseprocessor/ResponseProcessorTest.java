package org.veriagent.responseprocessor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.events.Payloads;
import org.veriagent.contracts.events.Topics;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.core.VeriagentProperties;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.bus.Events;
import org.veriagent.core.io.SafeFiles;
import org.veriagent.core.ledger.InvoiceLedger;
import org.veriagent.core.ledger.InvoiceLedger.Changes;
import org.veriagent.core.state.InvoiceState;
import org.veriagent.core.testsupport.CoreTestApplication;
import org.veriagent.core.testsupport.Fixtures;
import org.veriagent.provider.client.ProviderProtocol;
import org.veriagent.provider.client.ProvidersProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = CoreTestApplication.class, properties = {
    "veriagent.responses.poller-enabled=true", "veriagent.responses.poll-delay-ms=100",
    "veriagent.responses.receipt-grace=0s", "veriagent.responses.response-timeout=1h",
    "veriagent.responses.reconcile-interval=0s",
    "veriagent.providers.sftp-root=${java.io.tmpdir}/veriagent-test/sftp-${random.uuid}"})
class ResponseProcessorTest {
    @Autowired ResponseProcessor processor;
    @Autowired InvoiceLedger ledger;
    @Autowired EventBus bus;
    @Autowired VeriagentProperties core;
    @Autowired ProvidersProperties providers;

    private InvoiceRequest sent(String provider) {
        InvoiceRequest inv = Fixtures.coInvoice(Fixtures.number());
        ledger.register(inv, Payloads.Channel.BATCH, ContractJson.mapper().writeValueAsString(inv), "lote.json");
        ledger.transition(inv.invoiceKey(), InvoiceState.RECEIVED, InvoiceState.PENDING_BATCH, Changes.reason("x").withProvider(provider, 1));
        ledger.transition(inv.invoiceKey(), InvoiceState.PENDING_BATCH, InvoiceState.SENT, Changes.reason("x"));
        return inv;
    }

    private Path returnDir(String provider) throws Exception {
        Path p = ResponseConfiguration.returnRoot(core, providers).resolve(provider);
        Files.createDirectories(p);
        return p;
    }

    @Test
    @DisplayName("El resultado del lote (fuente primaria) en la carpeta de retorno autoriza y rechaza; el rechazo va a la DLQ")
    void processesReturnFolder() throws Exception {
        InvoiceRequest ok = sent("alfa"), ko = sent("alfa");
        List<Payloads.InvoiceDeadLettered> dlq = new CopyOnWriteArrayList<>();
        try (var s = bus.subscribe(Topics.ALL_INVOICE_DLQ, "prueba-resp-dlq", (t, e) -> dlq.add(Events.payload(e, Payloads.InvoiceDeadLettered.class)))) {
            var result = new ProviderProtocol.BatchResult("lote-1", "alfa", List.of(
                new ProviderProtocol.Decision(ok.invoiceKey(), "AUTHORIZED", "00", "Autorizado", "AUT-9", null),
                new ProviderProtocol.Decision(ko.invoiceKey(), "REJECTED", "R-CAT", "Catálogo", null, null)));
            Files.writeString(returnDir("alfa").resolve("lote-1.result.json"), ContractJson.mapper().writeValueAsString(result));
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline && ledger.find(ko.invoiceKey()).orElseThrow().status() == InvoiceState.SENT) Thread.sleep(100);
            assertTrue(bus.awaitQuiescence(Duration.ofSeconds(5)));
            assertEquals(InvoiceState.AUTHORIZED, ledger.find(ok.invoiceKey()).orElseThrow().status());
            assertEquals("AUT-9", ledger.find(ok.invoiceKey()).orElseThrow().authorizationNumber());
            assertEquals(InvoiceState.FAILED, ledger.find(ko.invoiceKey()).orElseThrow().status());
            assertEquals(1, dlq.size());
            assertEquals(Payloads.ErrorCategory.NON_RECOVERABLE, dlq.get(0).category());
        }
    }

    @Test
    @DisplayName("Una respuesta duplicada se ignora sin cambiar el estado ni duplicar efectos")
    void duplicateResponses() throws Exception {
        InvoiceRequest inv = sent("alfa");
        var d = new ProviderProtocol.Decision(inv.invoiceKey(), "AUTHORIZED", "00", "ok", "AUT-1", null);
        String json = ContractJson.mapper().writeValueAsString(new ProviderProtocol.BatchResult("lote-d", "alfa", List.of(d, d)));
        long dupBefore = processor.stats().get("duplicates");
        processor.processResultFile(json);
        assertTrue(bus.awaitQuiescence(Duration.ofSeconds(5)));
        assertEquals(InvoiceState.AUTHORIZED, ledger.find(inv.invoiceKey()).orElseThrow().status());
        assertEquals(dupBefore + 1, processor.stats().get("duplicates"));
        assertEquals(4, ledger.history(inv.invoiceKey()).size(), "RECEIVED, PENDING_BATCH, SENT y una sola AUTHORIZED");
    }

    @Test
    @DisplayName("ADR-06: sin respuesta del proveedor, la constancia oficial (respaldo) autoriza en la conciliación")
    void reconcilesWithReceipt() throws Exception {
        InvoiceRequest inv = sent("beta");
        Path receipts = returnDir("beta").resolve("receipts");
        Files.createDirectories(receipts);
        var r = new ProviderProtocol.Receipt(inv.invoiceKey(), "beta", "AUTHORIZED", "AUT-AUTORIDAD-1", "2026-10-19T00:00:00Z", "a".repeat(64));
        Files.writeString(receipts.resolve(SafeFiles.sanitize(inv.invoiceKey()) + ".receipt.json"), ContractJson.mapper().writeValueAsString(r));
        var res = processor.reconcile();
        assertTrue(res.fromReceipts() >= 1);
        assertTrue(bus.awaitQuiescence(Duration.ofSeconds(5)));
        var rec = ledger.find(inv.invoiceKey()).orElseThrow();
        assertEquals(InvoiceState.AUTHORIZED, rec.status());
        assertEquals("AUT-AUTORIDAD-1", rec.authorizationNumber());
        assertTrue(ledger.history(inv.invoiceKey()).get(3).reason().contains("constancia oficial"));
    }
}
