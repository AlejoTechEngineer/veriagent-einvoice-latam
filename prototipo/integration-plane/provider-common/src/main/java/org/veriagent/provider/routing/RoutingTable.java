package org.veriagent.provider.routing;

import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.model.InvoiceRequest;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Tabla de enrutamiento versionada (tabla 11: el enrutador elige el proveedor según país y tipo de documento).
 * Las reglas se evalúan en orden; gana la primera que coincide. {@code *} coincide con cualquier valor.
 */
public class RoutingTable {
    public record Rule(String id, String country, String documentType, String provider) {
        boolean matches(InvoiceRequest inv) {
            return ("*".equals(country) || country.equals(inv.country().name()))
                && ("*".equals(documentType) || documentType.equals(inv.documentType().name()));
        }
    }

    public record Route(String provider, String ruleId, int tableVersion) {}

    private final int version;
    private final String sha256;
    private final List<Rule> rules;

    public RoutingTable(int version, List<Rule> rules, String sha256) {
        this.version = version;
        this.rules = List.copyOf(rules);
        this.sha256 = sha256;
    }

    public static RoutingTable load(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            JsonNode root = ContractJson.mapper().readTree(bytes);
            List<Rule> rules = new ArrayList<>();
            for (JsonNode r : root.get("rules")) {
                String provider = r.get("provider").asString();
                if (!provider.matches("[a-z]+")) throw new IllegalArgumentException("Proveedor inválido en la regla: " + provider);
                rules.add(new Rule(r.get("id").asString(), r.get("country").asString(), r.get("documentType").asString(), provider));
            }
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new RoutingTable(root.get("version").asInt(), rules, sha);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer la tabla de enrutamiento " + file, e);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<Route> route(InvoiceRequest inv) {
        return rules.stream().filter(r -> r.matches(inv)).findFirst().map(r -> new Route(r.provider(), r.id(), version));
    }

    public int version() { return version; }
    public String sha256() { return sha256; }
    public List<Rule> rules() { return rules; }

    /** Proveedores que aparecen en la tabla. */
    public List<String> providers() {
        return rules.stream().map(Rule::provider).distinct().toList();
    }
}
