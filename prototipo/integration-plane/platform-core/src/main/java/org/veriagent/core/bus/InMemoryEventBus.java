package org.veriagent.core.bus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.veriagent.contracts.events.EventEnvelope;
import org.veriagent.contracts.events.Topics;
import org.veriagent.core.correlation.CorrelationContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Bus en memoria del perfil local. Cada suscripción tiene su propio hilo de entrega, así que los eventos de
 * un grupo se procesan en el orden en que se publicaron (y, por tanto, en orden por comprobante). La
 * publicación nunca bloquea al productor.
 */
public class InMemoryEventBus implements EventBus, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(InMemoryEventBus.class);

    private final boolean validateContracts;
    private final List<Sub> subscriptions = new CopyOnWriteArrayList<>();
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final Map<String, AtomicLong> published = new ConcurrentHashMap<>();

    public InMemoryEventBus(boolean validateContracts) {
        this.validateContracts = validateContracts;
    }

    @Override
    public void publish(String topic, EventEnvelope event) {
        if (!Topics.isValid(topic)) throw new IllegalArgumentException("Topic fuera de la convención: " + topic);
        if (validateContracts) Events.requireValid(event);
        published.computeIfAbsent(topic, t -> new AtomicLong()).incrementAndGet();
        for (Sub s : subscriptions) {
            if (s.pattern.matcher(topic).matches()) {
                pending.incrementAndGet();
                s.executor.execute(() -> deliver(s, topic, event));
            }
        }
    }

    private void deliver(Sub s, String topic, EventEnvelope event) {
        try {
            CorrelationContext.run(event.correlationId(), () -> {
                try {
                    s.handler.handle(topic, event);
                    delivered.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                    log.error("Fallo al entregar {} de {} al grupo {}: {}", event.eventType(), topic, s.group, e.toString(), e);
                }
            });
        } finally {
            pending.decrementAndGet();
        }
    }

    @Override
    public Subscription subscribe(String topicRegex, String group, EventHandler handler) {
        Sub s = new Sub(Pattern.compile(topicRegex), group, handler,
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "bus-" + group);
                t.setDaemon(true);
                return t;
            }));
        subscriptions.add(s);
        return () -> {
            subscriptions.remove(s);
            s.executor.shutdown();
        };
    }

    @Override
    public boolean awaitQuiescence(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        int stable = 0;
        while (System.nanoTime() < deadline) {
            stable = pending.get() == 0 ? stable + 1 : 0;
            if (stable >= 3) return true;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return pending.get() == 0;
    }

    public long deliveredCount() { return delivered.get(); }
    public long failedCount() { return failed.get(); }
    public long publishedCount(String topic) { return published.getOrDefault(topic, new AtomicLong()).get(); }

    @Override
    public void close() {
        subscriptions.forEach(s -> s.executor.shutdownNow());
        subscriptions.clear();
    }

    private record Sub(Pattern pattern, String group, EventHandler handler, ExecutorService executor) {}
}
