package org.veriagent.core.bus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.redpanda.RedpandaContainer;
import org.veriagent.contracts.events.EventTypes;
import org.veriagent.contracts.events.Payloads;
import org.veriagent.contracts.events.Topics;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Misma semántica del bus sobre Redpanda (perfil full). Requiere Docker: si el daemon no está disponible,
 * la prueba se omite y queda registrada como no ejecutada.
 */
@Testcontainers(disabledWithoutDocker = true)
class KafkaEventBusIT {
    @Container
    static final RedpandaContainer REDPANDA = new RedpandaContainer("redpandadata/redpanda:v26.2.5");

    @Test
    @DisplayName("Kafka: orden por clave, entrega por patrón y validación de contratos")
    void deliversInOrder() {
        try (KafkaEventBus bus = new KafkaEventBus(REDPANDA.getBootstrapServers(), true)) {
            bus.ensureTopics(KafkaEventBus.knownTopics(Map.of("CO", List.of("alfa"))), 3);
            List<Integer> got = new CopyOnWriteArrayList<>();
            bus.subscribe("^platform\\..*", "it-grupo",
                (t, e) -> got.add(Events.payload(e, Payloads.MappingActivated.class).version()));
            IntStream.rangeClosed(1, 50).forEach(i -> bus.publish(Topics.MAPPING_ACTIVATED,
                Events.of(EventTypes.MAPPING_ACTIVATED, null, null, "it",
                    new Payloads.MappingActivated("alfa", "CO", i, "a".repeat(64), "reviewer-a", null))));
            long deadline = System.currentTimeMillis() + 30_000;
            while (got.size() < 50 && System.currentTimeMillis() < deadline) Thread.onSpinWait();
            assertEquals(IntStream.rangeClosed(1, 50).boxed().toList(), got);
            assertTrue(bus.awaitQuiescence(Duration.ofSeconds(10)));
        }
    }
}
