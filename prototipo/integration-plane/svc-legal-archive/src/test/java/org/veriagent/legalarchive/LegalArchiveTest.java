package org.veriagent.legalarchive;

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
import org.veriagent.core.ledger.InvoiceLedger.Changes;
import org.veriagent.core.state.InvoiceState;
import org.veriagent.core.testsupport.CoreTestApplication;
import org.veriagent.core.testsupport.Fixtures;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = CoreTestApplication.class,
    properties = "veriagent.archive.root=${java.io.tmpdir}/veriagent-test/archivo-${random.uuid}")
class LegalArchiveTest {
    @Autowired LegalArchive archive;
    @Autowired InvoiceLedger ledger;
    @Autowired FlowEvents flow;
    @Autowired EventBus bus;
    @Autowired JdbcClient jdbc;

    private InvoiceRequest authorized() {
        InvoiceRequest inv = Fixtures.coInvoice(Fixtures.number());
        ledger.register(inv, Payloads.Channel.SYNC, ContractJson.mapper().writeValueAsString(inv));
        ledger.transition(inv.invoiceKey(), InvoiceState.RECEIVED, InvoiceState.SENT,
            Changes.reason("x").withProvider("alfa", 1).withPayload("<Invoice><ID>" + inv.documentNumber() + "</ID></Invoice>"));
        ledger.transition(inv.invoiceKey(), InvoiceState.SENT, InvoiceState.AUTHORIZED, Changes.reason("x").withAuthorization("AUT"));
        flow.response(inv.invoiceKey(), "CO", "prueba", Payloads.FinalStatus.AUTHORIZED, Payloads.Channel.SYNC, "alfa", "OK", "ok", "AUT", null);
        return inv;
    }

    @Test
    @DisplayName("Archiva lo autorizado con cadena de huellas válida, de forma idempotente y con retención por país")
    void archivesWithHashChain() throws Exception {
        InvoiceRequest a = authorized(), b = authorized();
        assertTrue(bus.awaitQuiescence(Duration.ofSeconds(5)));
        var ea = archive.find(a.invoiceKey()).orElseThrow();
        var eb = archive.find(b.invoiceKey()).orElseThrow();
        assertEquals(ea.entryHash(), eb.prevHash(), "cada entrada se encadena con la anterior");
        assertEquals(ea.archivedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate().plusYears(5), ea.retentionUntil());
        archive.archive(a.invoiceKey());
        assertEquals(eb.seq(), archive.find(b.invoiceKey()).orElseThrow().seq());
        assertEquals(new LegalArchive.Verification(true, archive.list(500).size(), null, null), archive.verify());
        assertFalse(Files.isWritable(archive.root().resolve(ea.objectPath())), "el objeto queda de solo lectura");
    }

    @Test
    @DisplayName("Cualquier alteración de un objeto archivado rompe la verificación")
    void detectsTampering() throws Exception {
        InvoiceRequest a = authorized();
        assertTrue(bus.awaitQuiescence(Duration.ofSeconds(5)));
        var e = archive.find(a.invoiceKey()).orElseThrow();
        Path f = archive.root().resolve(e.objectPath());
        f.toFile().setWritable(true);
        Files.writeString(f, "<Invoice>alterado</Invoice>");
        var v = archive.verify();
        assertFalse(v.valid());
        assertEquals(e.seq(), v.brokenAtSeq());
        assertEquals("El objeto almacenado fue alterado", v.problem());
        Files.writeString(f, "<Invoice><ID>" + a.documentNumber() + "</ID></Invoice>");
    }

    @Test
    @DisplayName("ADR-07: si el almacenamiento falla, el archivo queda pendiente con reintento y no afecta al flujo")
    void bestEffortWithRetry() throws Exception {
        Path brokenRoot = Files.createTempFile("no-es-un-directorio", ".txt");
        LegalArchive broken = new LegalArchive(bus, ledger, jdbc, brokenRoot, Map.of("CO", 5));
        InvoiceRequest inv = authorized();
        assertTrue(bus.awaitQuiescence(Duration.ofSeconds(5)));
        jdbc.sql("DELETE FROM legal_archive_entry WHERE invoice_key = :k").param("k", inv.invoiceKey()).update();
        broken.tryArchive(inv.invoiceKey());
        assertEquals(1, jdbc.sql("SELECT attempts FROM archive_pending WHERE invoice_key = :k").param("k", inv.invoiceKey()).query(Integer.class).single());
        assertEquals(InvoiceState.AUTHORIZED, ledger.find(inv.invoiceKey()).orElseThrow().status(), "el comprobante sigue autorizado");
        archive.tryArchive(inv.invoiceKey());
        assertTrue(archive.find(inv.invoiceKey()).isPresent());
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM archive_pending WHERE invoice_key = :k").param("k", inv.invoiceKey()).query(Long.class).single());
    }
}
