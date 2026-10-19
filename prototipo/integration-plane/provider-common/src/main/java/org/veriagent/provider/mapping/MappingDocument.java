package org.veriagent.provider.mapping;

import org.veriagent.contracts.ContractJson;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Versión de un mapeo canónico → formato de proveedor para un país. El contenido es el mismo formato JSON que
 * propone el agente de mapeo (golden/mapping-alfa-v2.json, golden/mapping-beta.json). La huella SHA-256 del
 * contenido canónico identifica la versión de forma inequívoca.
 */
public record MappingDocument(String provider, String country, int version, String profile, JsonNode body,
                              String sha256) {

    public static MappingDocument of(String provider, String country, int version, JsonNode body) {
        String profile = body.has("profile") ? body.get("profile").asString() : "desconocido";
        return new MappingDocument(provider, country, version, profile, body, sha256(body));
    }

    public static String sha256(JsonNode body) {
        try {
            byte[] canonical = ContractJson.mapper().writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
