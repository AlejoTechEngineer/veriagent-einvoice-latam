package org.veriagent.provider.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Configuración de los proveedores (prefijo {@code veriagent.providers}). */
@ConfigurationProperties("veriagent.providers")
public class ProvidersProperties {
    /** Tabla de enrutamiento versionada (relativa a {@code veriagent.home}). */
    private String routingTable = "data/routing/routing-table.v1.json";
    /** Carpeta raíz del SFTP simulado (relativa a {@code veriagent.home}). */
    private String sftpRoot = "results/runtime/sftp";
    private final Map<String, Provider> endpoints = new LinkedHashMap<>();
    /** Mapeos sembrados al arrancar: clave {@code proveedor/PAIS}, valor ruta del JSON (relativa a home). */
    private final Map<String, String> seedMappings = new LinkedHashMap<>();

    public String getRoutingTable() { return routingTable; }
    public void setRoutingTable(String routingTable) { this.routingTable = routingTable; }
    public String getSftpRoot() { return sftpRoot; }
    public void setSftpRoot(String sftpRoot) { this.sftpRoot = sftpRoot; }
    public Map<String, Provider> getEndpoints() { return endpoints; }
    public Map<String, String> getSeedMappings() { return seedMappings; }

    public static class Provider {
        private String baseUrl;
        /** {@code json-envelope} (Alfa, Gamma) o {@code text} (Beta). */
        private String syncFormat = "json-envelope";
        /** {@code http-zip} (Alfa) o {@code sftp-folder} (Beta). */
        private String batchMode = "http-zip";
        private Duration timeout = Duration.ofSeconds(5);
        private int batchSize = 100;
        private Duration batchWindow = Duration.ofMillis(500);

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getSyncFormat() { return syncFormat; }
        public void setSyncFormat(String syncFormat) { this.syncFormat = syncFormat; }
        public String getBatchMode() { return batchMode; }
        public void setBatchMode(String batchMode) { this.batchMode = batchMode; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public Duration getBatchWindow() { return batchWindow; }
        public void setBatchWindow(Duration batchWindow) { this.batchWindow = batchWindow; }
    }
}
