package org.veriagent.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** Configuración común de la plataforma (prefijo {@code veriagent}). */
@ConfigurationProperties("veriagent")
public class VeriagentProperties {
    /** Raíz del prototipo: specs/, catalogs/, schemas/ y data/. */
    private String home = "..";
    private final Bus bus = new Bus();
    private final Security security = new Security();
    private final Retry retry = new Retry();
    private final Limits limits = new Limits();

    public String getHome() { return home; }
    public void setHome(String home) { this.home = home; }
    public Bus getBus() { return bus; }
    public Security getSecurity() { return security; }
    public Retry getRetry() { return retry; }
    public Limits getLimits() { return limits; }

    public static class Bus {
        /** {@code memory} (perfil local) o {@code kafka} (perfil full, Redpanda). */
        private String type = "memory";
        private String bootstrapServers = "localhost:9092";
        /** Valida cada evento publicado contra su esquema JSON versionado. */
        private boolean validateContracts = true;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getBootstrapServers() { return bootstrapServers; }
        public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }
        public boolean isValidateContracts() { return validateContracts; }
        public void setValidateContracts(boolean validateContracts) { this.validateContracts = validateContracts; }
    }

    public static class Security {
        /** Archivo local (ignorado por git) con los tokens de demostración. */
        private String tokensFile = "results/runtime/demo-tokens.json";
        /** {@code generate}: crea tokens nuevos al arrancar; {@code load}: lee los de otro proceso. */
        private String tokenMode = "generate";
        /** Orígenes permitidos por CORS (el del front). */
        private List<String> allowedOrigins = List.of("http://127.0.0.1:8080", "http://localhost:8080");

        public String getTokensFile() { return tokensFile; }
        public void setTokensFile(String tokensFile) { this.tokensFile = tokensFile; }
        public String getTokenMode() { return tokenMode; }
        public void setTokenMode(String tokenMode) { this.tokenMode = tokenMode; }
        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    }

    public static class Retry {
        private int maxAttempts = 5;
        private Duration initialInterval = Duration.ofMillis(200);
        private double multiplier = 2.0;
        private Duration maxInterval = Duration.ofSeconds(5);

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Duration getInitialInterval() { return initialInterval; }
        public void setInitialInterval(Duration initialInterval) { this.initialInterval = initialInterval; }
        public double getMultiplier() { return multiplier; }
        public void setMultiplier(double multiplier) { this.multiplier = multiplier; }
        public Duration getMaxInterval() { return maxInterval; }
        public void setMaxInterval(Duration maxInterval) { this.maxInterval = maxInterval; }
    }

    public static class Limits {
        /** Tamaño máximo de un lote cargado (bytes). */
        private long maxBatchBytes = 20L * 1024 * 1024;
        /** Tamaño máximo descomprimido de un ZIP (protección contra bombas de descompresión). */
        private long maxUncompressedBytes = 100L * 1024 * 1024;
        /** Número máximo de comprobantes por lote. */
        private int maxInvoicesPerBatch = 20_000;

        public long getMaxBatchBytes() { return maxBatchBytes; }
        public void setMaxBatchBytes(long maxBatchBytes) { this.maxBatchBytes = maxBatchBytes; }
        public long getMaxUncompressedBytes() { return maxUncompressedBytes; }
        public void setMaxUncompressedBytes(long maxUncompressedBytes) { this.maxUncompressedBytes = maxUncompressedBytes; }
        public int getMaxInvoicesPerBatch() { return maxInvoicesPerBatch; }
        public void setMaxInvoicesPerBatch(int maxInvoicesPerBatch) { this.maxInvoicesPerBatch = maxInvoicesPerBatch; }
    }
}
