package org.veriagent.provider.mapping;

import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.model.InvoiceRequest;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Ejemplos validados por personas ({@code data/fixtures/{proveedor}-{PAIS}.json}): para cada comprobante canónico
 * curado, el documento que una persona aprobó. Son la referencia del control de integración continua (ADR-05): un
 * mapeo candidato debe reproducirlos hoja por hoja. No se recalculan con el mapeo vigente, para que un mapeo
 * defectuoso nunca se convierta en su propia referencia.
 */
public final class ValidatedFixtures {
    public record Fixture(InvoiceRequest canonical, String expected) {}

    private ValidatedFixtures() {}

    public static Path file(Path home, String provider, String country) {
        return home.resolve("data/fixtures").resolve(provider + "-" + country + ".json");
    }

    public static boolean exists(Path home, String provider, String country) {
        return Files.exists(file(home, provider, country));
    }

    public static List<Fixture> load(Path home, String provider, String country) {
        try {
            JsonNode root = ContractJson.mapper().readTree(Files.readString(file(home, provider, country)));
            List<Fixture> out = new ArrayList<>();
            for (JsonNode f : root.get("fixtures")) {
                out.add(new Fixture(ContractJson.mapper().treeToValue(f.get("canonical"), InvoiceRequest.class), f.get("expected").asString()));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudieron leer los ejemplos validados de " + provider + "/" + country, e);
        }
    }
}
