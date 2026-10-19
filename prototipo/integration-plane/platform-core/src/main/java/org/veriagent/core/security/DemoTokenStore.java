package org.veriagent.core.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.veriagent.contracts.ContractJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tokens de demostración (no es autenticación real). Se generan aleatoriamente al arrancar y se escriben en un
 * archivo local ignorado por git, para que el front, los scripts y el plano agéntico los lean. En el repositorio
 * no hay ningún valor fijo. En memoria solo se guarda la huella SHA-256 de cada token.
 */
public class DemoTokenStore {
    private static final Logger log = LoggerFactory.getLogger(DemoTokenStore.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Identidades de demostración por defecto. */
    public static final List<Actor> DEFAULT_IDENTITIES = List.of(
        new Actor("revisor.demo", Role.REVIEWER),
        new Actor("revisora.dos", Role.REVIEWER),
        new Actor("agente.mapeo", Role.AGENT),
        new Actor("operador.demo", Role.OPERATOR),
        new Actor("sistema", Role.SYSTEM));

    private final Map<String, Actor> byHash = new ConcurrentHashMap<>();

    /** Genera tokens nuevos para las identidades y los escribe en el archivo (lo reemplaza). */
    public static DemoTokenStore generate(Path file, List<Actor> identities) {
        DemoTokenStore store = new DemoTokenStore();
        Map<String, Actor> plain = new LinkedHashMap<>();
        for (Actor a : identities) {
            String token = newToken();
            plain.put(token, a);
            store.byHash.put(sha256(token), a);
        }
        write(file, plain);
        log.info("Tokens de demostración generados en {} ({} identidades)", file.toAbsolutePath(), identities.size());
        return store;
    }

    /** Carga los tokens que generó otro proceso. */
    public static DemoTokenStore load(Path file) {
        DemoTokenStore store = new DemoTokenStore();
        try {
            JsonNode root = ContractJson.mapper().readTree(Files.readString(file, StandardCharsets.UTF_8));
            for (JsonNode n : root.get("tokens")) {
                store.byHash.put(sha256(n.get("token").asString()),
                    new Actor(n.get("subject").asString(), Role.valueOf(n.get("role").asString())));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el archivo de tokens " + file, e);
        }
        return store;
    }

    /** Identidad asociada al token, si existe. */
    public Optional<Actor> resolve(String token) {
        if (token == null || token.length() < 20 || token.length() > 200) return Optional.empty();
        return Optional.ofNullable(byHash.get(sha256(token)));
    }

    /** Emite un token adicional (solo para pruebas y escenarios); no se escribe en el archivo. */
    public String issue(Actor actor) {
        String token = newToken();
        byHash.put(sha256(token), actor);
        return token;
    }

    private static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return "demo_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(Path file, Map<String, Actor> plain) {
        ObjectNode root = ContractJson.mapper().createObjectNode();
        root.put("warning", "Tokens de demostración del prototipo. No son credenciales reales. No subir al repositorio.");
        root.put("generatedAt", Instant.now().toString());
        ArrayNode arr = root.putArray("tokens");
        plain.forEach((token, a) -> arr.addObject().put("subject", a.subject()).put("role", a.role().name()).put("token", token));
        try {
            Path abs = file.toAbsolutePath();
            Files.createDirectories(abs.getParent());
            Path tmp = abs.resolveSibling(abs.getFileName() + ".tmp");
            Files.writeString(tmp, ContractJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
            Files.move(tmp, abs, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo escribir el archivo de tokens " + file, e);
        }
    }
}
