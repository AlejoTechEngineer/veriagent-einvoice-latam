package org.veriagent.core.bus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.veriagent.contracts.events.EventEnvelope;
import org.veriagent.contracts.events.EventTypes;
import org.veriagent.contracts.events.Payloads;
import org.veriagent.contracts.events.Topics;
import org.veriagent.contracts.model.Country;
import org.veriagent.core.correlation.CorrelationContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryEventBusTest {
    private final InMemoryEventBus bus = new InMemoryEventBus(true);

    @AfterEach
    void close() { bus.close(); }

    private static EventEnvelope mapping(int version) {
        return Events.of(EventTypes.MAPPING_ACTIVATED, null, null, "prueba",
            new Payloads.MappingActivated("alfa", "CO", version, "a".repeat(64), "revisor.demo", null));
    }

    @Test
    @DisplayName("Entrega en orden de publicación dentro de cada grupo y una copia por grupo")
    void ordersAndFansOut() {
        List<Integer> a = Collections.synchronizedList(new ArrayList<>());
        List<Integer> b = Collections.synchronizedList(new ArrayList<>());
        bus.subscribe("^platform\\.mapping\\.activated\\.v1$", "grupo-a",
            (t, e) -> a.add(Events.payload(e, Payloads.MappingActivated.class).version()));
        bus.subscribe("^platform\\..*", "grupo-b",
            (t, e) -> b.add(Events.payload(e, Payloads.MappingActivated.class).version()));
        IntStream.rangeClosed(1, 200).forEach(i -> bus.publish(Topics.MAPPING_ACTIVATED, mapping(i)));
        assertTrue(bus.awaitQuiescence(Duration.ofSeconds(10)));
        List<Integer> expected = IntStream.rangeClosed(1, 200).boxed().toList();
        assertEquals(expected, a);
        assertEquals(expected, b);
        assertEquals(200, bus.publishedCount(Topics.MAPPING_ACTIVATED));
    }

    @Test
    @DisplayName("Las suscripciones por expresión regular solo reciben sus topics")
    void routesByRegex() {
        List<String> got = new CopyOnWriteArrayList<>();
        bus.subscribe(Topics.ALL_INVOICE_DLQ, "dlq", (t, e) -> got.add(t));
        var dead = new Payloads.InvoiceDeadLettered("PROVIDER", Payloads.ErrorCategory.RECOVERABLE, 3, "caído", "beta", List.of());
        bus.publish(Topics.invoiceDlq(Country.PE), Events.of(EventTypes.INVOICE_DEAD_LETTERED, "PE", "K1", "prueba", dead));
        bus.publish(Topics.MAPPING_ACTIVATED, mapping(1));
        assertTrue(bus.awaitQuiescence(Duration.ofSeconds(5)));
        assertEquals(List.of("pe.invoice.dlq.v1"), got);
    }

    @Test
    @DisplayName("No se publica un evento que incumple su contrato ni en un topic fuera de la convención")
    void rejectsInvalidEvents() {
        var bad = Events.of(EventTypes.MAPPING_ACTIVATED, null, null, "prueba",
            new Payloads.MappingActivated("ALFA", "CO", 0, "corto", "", null));
        assertThrows(Events.ContractViolationException.class, () -> bus.publish(Topics.MAPPING_ACTIVATED, bad));
        assertThrows(IllegalArgumentException.class, () -> bus.publish("otro.topic", mapping(1)));
    }

    @Test
    @DisplayName("La correlación del evento viaja al hilo del consumidor y un fallo del manejador no detiene el bus")
    void propagatesCorrelationAndSurvivesFailures() {
        List<String> seen = new CopyOnWriteArrayList<>();
        bus.subscribe("^platform\\..*", "fallos", (t, e) -> {
            seen.add(CorrelationContext.current());
            if (Events.payload(e, Payloads.MappingActivated.class).version() == 2) throw new IllegalStateException("fallo");
        });
        CorrelationContext.run("corr-prueba-1", () -> bus.publish(Topics.MAPPING_ACTIVATED, mapping(1)));
        CorrelationContext.run("corr-prueba-2", () -> bus.publish(Topics.MAPPING_ACTIVATED, mapping(2)));
        CorrelationContext.run("corr-prueba-3", () -> bus.publish(Topics.MAPPING_ACTIVATED, mapping(3)));
        assertTrue(bus.awaitQuiescence(Duration.ofSeconds(5)));
        assertEquals(List.of("corr-prueba-1", "corr-prueba-2", "corr-prueba-3"), seen);
        assertEquals(1, bus.failedCount());
        assertEquals(2, bus.deliveredCount());
    }
}
