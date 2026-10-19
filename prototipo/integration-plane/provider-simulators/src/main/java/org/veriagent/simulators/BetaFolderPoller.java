package org.veriagent.simulators;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.veriagent.contracts.ContractJson;
import org.veriagent.core.VeriagentProperties;
import org.veriagent.provider.client.ProviderProtocol.BatchManifest;
import org.veriagent.simulators.FaultInjector.Fault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Proveedor Beta por lotes: revisa su carpeta SFTP simulada, lee cada archivo de segmentos con su manifiesto,
 * separa los documentos (cada uno empieza por un segmento ENC) y los procesa. Los archivos leídos pasan a
 * {@code .procesados/} y los ilegibles a {@code .rechazados/}.
 */
public class BetaFolderPoller {
    private static final Logger log = LoggerFactory.getLogger(BetaFolderPoller.class);

    private final ProviderSimulator sim;
    private final Path inbox;
    private final Duration delay;
    private final VeriagentProperties.Limits limits;
    private ScheduledExecutorService scheduler;

    public BetaFolderPoller(ProviderSimulator sim, Path inbox, Duration delay, VeriagentProperties.Limits limits) {
        this.sim = sim;
        this.inbox = inbox;
        this.delay = delay;
        this.limits = limits;
    }

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "simulador-beta-sftp");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::safePoll, delay.toMillis(), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    private void safePoll() {
        try {
            poll();
        } catch (RuntimeException e) {
            log.error("Simulador Beta: error revisando la carpeta de entrada", e);
        }
    }

    /** Procesa los lotes completos presentes en la carpeta. Devuelve cuántos procesó. */
    public int poll() {
        if (!Files.isDirectory(inbox)) return 0;
        List<Path> ready = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(inbox, "*.seg")) {
            ds.forEach(ready::add);
        } catch (IOException e) {
            return 0;
        }
        int n = 0;
        for (Path seg : ready) {
            String base = seg.getFileName().toString().replaceFirst("\\.seg$", "");
            Path manifestFile = inbox.resolve(base + ".manifest.json");
            if (!Files.exists(manifestFile)) continue;
            try {
                if (Files.size(seg) > limits.getMaxBatchBytes()) throw new IllegalArgumentException("Lote demasiado grande");
                BatchManifest manifest = ContractJson.mapper().readValue(Files.readString(manifestFile), BatchManifest.class);
                List<String> docs = split(Files.readString(seg, StandardCharsets.UTF_8));
                if (docs.size() != manifest.documents().size()) {
                    throw new IllegalArgumentException("El manifiesto declara " + manifest.documents().size() + " documentos y el archivo trae " + docs.size());
                }
                Map<String, String> entries = new HashMap<>();
                for (int i = 0; i < docs.size(); i++) entries.put(manifest.documents().get(i).entryName(), docs.get(i));
                Fault fault = sim.faults().next("beta", null).orElse(null);
                if (fault == Fault.UNAVAILABLE) {
                    sim.count("beta", "fault-unavailable");
                    continue; // el proveedor "no lee" su carpeta; lo intentará en la siguiente revisión
                }
                sim.processBatch("beta", manifest, entries, fault);
                move(seg, ".procesados");
                move(manifestFile, ".procesados");
                n++;
            } catch (IOException | RuntimeException e) {
                log.warn("Simulador Beta: lote ilegible {}: {}", seg.getFileName(), e.getMessage());
                move(seg, ".rechazados");
                move(manifestFile, ".rechazados");
            }
        }
        return n;
    }

    /** Separa los documentos de un archivo de segmentos: cada documento empieza por ENC. */
    static List<String> split(String text) {
        List<String> docs = new ArrayList<>();
        StringBuilder cur = null;
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) continue;
            if (line.startsWith("ENC|")) {
                if (cur != null) docs.add(cur.toString());
                cur = new StringBuilder();
            }
            if (cur == null) throw new IllegalArgumentException("El archivo no empieza por un segmento ENC");
            cur.append(line).append('\n');
        }
        if (cur != null) docs.add(cur.toString());
        return docs;
    }

    private void move(Path file, String folder) {
        try {
            Path dir = inbox.resolve(folder);
            Files.createDirectories(dir);
            Files.move(file, dir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("No se pudo mover {}: {}", file, e.toString());
        }
    }
}
