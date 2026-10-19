package org.veriagent.core.bus;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.veriagent.contracts.events.EventEnvelope;
import org.veriagent.contracts.events.Topics;
import org.veriagent.core.correlation.CorrelationContext;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Bus sobre Kafka (Redpanda en el perfil full). Clave = clave del comprobante, para conservar el orden por
 * comprobante dentro de cada partición. El consumidor confirma el desplazamiento solo después de procesar el
 * lote (entrega al menos una vez); la idempotencia la garantizan los servicios.
 */
public class KafkaEventBus implements EventBus, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(KafkaEventBus.class);

    private final String bootstrap;
    private final boolean validateContracts;
    private final KafkaProducer<String, String> producer;
    private final List<Consumer> consumers = new CopyOnWriteArrayList<>();
    private final AtomicLong inFlight = new AtomicLong();

    public KafkaEventBus(String bootstrapServers, boolean validateContracts) {
        this.bootstrap = bootstrapServers;
        this.validateContracts = validateContracts;
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        this.producer = new KafkaProducer<>(p);
    }

    /** Crea los topics conocidos para que las suscripciones por patrón los vean desde el inicio. */
    public void ensureTopics(Collection<String> topics, int partitions) {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        try (Admin admin = Admin.create(p)) {
            for (String t : topics) {
                try {
                    admin.createTopics(List.of(new NewTopic(t, partitions, (short) 1))).all().get();
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) throw new IllegalStateException(e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void publish(String topic, EventEnvelope event) {
        if (!Topics.isValid(topic)) throw new IllegalArgumentException("Topic fuera de la convención: " + topic);
        if (validateContracts) Events.requireValid(event);
        String key = event.invoiceKey() != null ? event.invoiceKey() : event.eventId();
        try {
            producer.send(new ProducerRecord<>(topic, key, Events.toJson(event))).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publicación interrumpida", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("No se pudo publicar en " + topic + ": " + e.getCause(), e.getCause());
        }
    }

    @Override
    public Subscription subscribe(String topicRegex, String group, EventHandler handler) {
        Consumer c = new Consumer(Pattern.compile(topicRegex), group, handler);
        consumers.add(c);
        c.thread.start();
        return () -> {
            c.stop();
            consumers.remove(c);
        };
    }

    @Override
    public boolean awaitQuiescence(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        int stable = 0;
        while (System.nanoTime() < deadline) {
            boolean idle = inFlight.get() == 0 && consumers.stream().allMatch(Consumer::caughtUp);
            stable = idle ? stable + 1 : 0;
            if (stable >= 5) return true;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Override
    public void close() {
        consumers.forEach(Consumer::stop);
        producer.close(Duration.ofSeconds(5));
    }

    private final class Consumer implements Runnable {
        private final Pattern pattern;
        private final String group;
        private final EventHandler handler;
        private final KafkaConsumer<String, String> consumer;
        private final Thread thread;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private volatile boolean lastPollEmpty = false;

        Consumer(Pattern pattern, String group, EventHandler handler) {
            this.pattern = pattern;
            this.group = group;
            this.handler = handler;
            Properties p = new Properties();
            p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
            p.put(ConsumerConfig.GROUP_ID_CONFIG, group);
            p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
            p.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, "2000");
            this.consumer = new KafkaConsumer<>(p);
            this.thread = new Thread(this, "kafka-" + group);
            this.thread.setDaemon(true);
        }

        boolean caughtUp() { return lastPollEmpty; }

        @Override
        public void run() {
            try {
                consumer.subscribe(pattern);
                while (running.get()) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));
                    lastPollEmpty = records.isEmpty();
                    for (ConsumerRecord<String, String> r : records) {
                        inFlight.incrementAndGet();
                        try {
                            EventEnvelope e = Events.fromJson(r.value());
                            CorrelationContext.call(e.correlationId(), () -> { handler.handle(r.topic(), e); return null; });
                        } catch (Exception ex) {
                            log.error("Fallo al procesar {} en el grupo {}: {}", r.topic(), group, ex.toString(), ex);
                        } finally {
                            inFlight.decrementAndGet();
                        }
                    }
                    if (!records.isEmpty()) consumer.commitSync();
                }
            } catch (WakeupException e) {
                if (running.get()) throw e;
            } finally {
                consumer.close();
            }
        }

        void stop() {
            running.set(false);
            consumer.wakeup();
        }
    }

    /** Topics conocidos de la plataforma para países y proveedores dados. */
    public static List<String> knownTopics(Map<String, List<String>> providersByCountry) {
        List<String> out = new CopyOnWriteArrayList<>();
        providersByCountry.forEach((country, providers) -> {
            String c = country.toLowerCase(java.util.Locale.ROOT);
            out.add(c + ".invoice.request.v1");
            out.add(c + ".invoice.response.v1");
            out.add(c + ".invoice.dlq.v1");
            for (String p : providers) {
                out.add(c + "." + p + ".request.v1");
                out.add(c + "." + p + ".response.v1");
            }
        });
        out.add(Topics.MAPPING_ACTIVATED);
        return out;
    }
}
