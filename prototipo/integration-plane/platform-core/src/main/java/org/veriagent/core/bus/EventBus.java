package org.veriagent.core.bus;

import org.veriagent.contracts.events.EventEnvelope;

import java.time.Duration;

/**
 * Bus de eventos de la plataforma. Dos implementaciones con la misma semántica: en memoria (perfil local) y
 * Kafka/Redpanda (perfil full). Entrega al menos una vez y en orden por clave de comprobante dentro de cada
 * grupo de consumidores.
 */
public interface EventBus {

    /** Publica el evento en el topic; la clave de partición es la clave del comprobante (o el id del evento). */
    void publish(String topic, EventEnvelope event);

    /**
     * Suscribe un manejador a todos los topics que cumplen la expresión regular. Cada grupo recibe una copia;
     * dentro de un grupo, cada evento se entrega a un solo manejador.
     */
    Subscription subscribe(String topicRegex, String group, EventHandler handler);

    /** Espera a que no queden eventos pendientes de entrega (para pruebas y escenarios). */
    boolean awaitQuiescence(Duration timeout);

    /** Manejador de eventos. Si lanza una excepción, el bus la registra y el evento cuenta como fallido. */
    @FunctionalInterface
    interface EventHandler {
        void handle(String topic, EventEnvelope event) throws Exception;
    }

    /** Suscripción activa; se cancela al cerrarla. */
    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
