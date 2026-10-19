package org.veriagent.simulators;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tfg.kit.core.Mode;
import org.veriagent.contracts.ContractJson;
import org.veriagent.core.io.SafeFiles;
import org.veriagent.kit.KitResult;
import org.veriagent.kit.KitVerifier;
import org.veriagent.provider.client.ProviderClient;
import org.veriagent.provider.client.ProviderProtocol.BatchManifest;
import org.veriagent.provider.client.ProviderProtocol.BatchResult;
import org.veriagent.provider.client.ProviderProtocol.Decision;
import org.veriagent.provider.client.ProviderProtocol.Receipt;
import org.veriagent.simulators.FaultInjector.Fault;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lógica común de los proveedores simulados. Cada proveedor valida el documento contra su especificación
 * publicada (el mismo perfil del kit) y decide de forma determinista: el mismo documento produce siempre la misma
 * decisión y el mismo número de autorización. Deja el resultado de los lotes en la carpeta de retorno y la
 * constancia oficial de cada comprobante autorizado en {@code receipts/} (respaldo, ADR-06).
 */
public class ProviderSimulator {
    public static final Set<String> PROVIDERS = Set.of("alfa", "beta", "gamma");
    private static final Logger log = LoggerFactory.getLogger(ProviderSimulator.class);

    private final KitVerifier kit;
    private final FaultInjector faults;
    private final Path returnRoot;
    private final Map<String, Map<String, AtomicLong>> counters = new ConcurrentHashMap<>();

    public ProviderSimulator(KitVerifier kit, FaultInjector faults, Path returnRoot) {
        this.kit = kit;
        this.faults = faults;
        this.returnRoot = returnRoot;
    }

    public FaultInjector faults() { return faults; }

    public static String requireProvider(String provider) {
        String p = provider == null ? "" : provider.toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(p)) throw new java.util.NoSuchElementException("Proveedor simulado desconocido: " + provider);
        return p;
    }

    /** Perfil de la especificación de cada proveedor según el país del comprobante. */
    static String specProfile(String provider, String documentId) {
        String country = documentId.length() > 2 ? documentId.substring(0, 2) : "";
        return switch (provider) {
            case "beta" -> "PE".equals(country) ? "beta-pe" : "beta";
            default -> provider;
        };
    }

    /** Valida y decide un documento (sin fallos). */
    public Decision decide(String provider, String documentId, String content, boolean rejectCatalog) {
        count(provider, "received");
        if (rejectCatalog) {
            count(provider, "rejected");
            return new Decision(documentId, "REJECTED", "CAT-01",
                "Catálogo del proveedor actualizado: un código del documento ya no está vigente", null, null);
        }
        KitResult v = kit.verify(specProfile(provider, documentId), documentId, content, Mode.RUNTIME, null);
        if (!v.passed()) {
            count(provider, "rejected");
            var f = v.findings().get(0);
            return new Decision(documentId, "REJECTED", "VAL-" + f.ruleId(), f.message(), null, null);
        }
        count(provider, "authorized");
        String sha = ProviderClient.sha256(content);
        String auth = provider.toUpperCase(Locale.ROOT) + "-" + ProviderClient.sha256(provider + "|" + documentId).substring(0, 12).toUpperCase(Locale.ROOT);
        writeReceipt(provider, new Receipt(documentId, provider, "AUTHORIZED", auth, Instant.now().toString(), sha));
        return new Decision(documentId, "AUTHORIZED", "00", "Autorizado", auth, sha);
    }

    /** Procesa un lote completo y deja el resultado en la carpeta de retorno según el fallo activo. */
    public BatchResult processBatch(String provider, BatchManifest manifest, Map<String, String> entries, Fault fault) {
        List<Decision> results = new ArrayList<>();
        for (var d : manifest.documents()) {
            String content = entries.get(d.entryName());
            if (content == null) {
                results.add(new Decision(d.documentId(), "REJECTED", "LOT-01", "El documento no está en el lote", null, null));
                continue;
            }
            results.add(decide(provider, d.documentId(), content, fault == Fault.REJECT_CATALOG));
        }
        BatchResult result = new BatchResult(manifest.batchId(), provider, results);
        count(provider, "batches");
        if (fault == Fault.LOST_RESPONSE) {
            log.warn("Simulador {}: el resultado del lote {} se pierde (fallo inyectado)", provider, manifest.batchId());
            return result;
        }
        writeResult(provider, manifest.batchId(), result);
        if (fault == Fault.DUPLICATE) writeResult(provider, manifest.batchId() + "-dup", result);
        return result;
    }

    /** Resultado suelto (respuesta duplicada de un envío síncrono). */
    public void writeResult(String provider, String batchId, BatchResult result) {
        write(returnRoot.resolve(provider), SafeFiles.sanitize(batchId) + ".result.json",
            ContractJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }

    private void writeReceipt(String provider, Receipt r) {
        write(returnRoot.resolve(provider).resolve("receipts"), SafeFiles.sanitize(r.documentId()) + ".receipt.json",
            ContractJson.mapper().writeValueAsString(r));
    }

    private static void write(Path dir, String name, String content) {
        try {
            Files.createDirectories(dir);
            Path target = SafeFiles.resolveInside(dir, name);
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir " + name, e);
        }
    }

    void count(String provider, String what) {
        counters.computeIfAbsent(provider, k -> new ConcurrentHashMap<>()).computeIfAbsent(what, k -> new AtomicLong()).incrementAndGet();
    }

    public Map<String, Map<String, Long>> stats() {
        Map<String, Map<String, Long>> out = new java.util.TreeMap<>();
        counters.forEach((p, m) -> {
            Map<String, Long> c = new java.util.TreeMap<>();
            m.forEach((k, v) -> c.put(k, v.get()));
            out.put(p, c);
        });
        return out;
    }
}
