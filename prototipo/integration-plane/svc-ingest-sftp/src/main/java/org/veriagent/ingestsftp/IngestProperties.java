package org.veriagent.ingestsftp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuración de la ingesta (prefijo {@code veriagent.ingest}). */
@ConfigurationProperties("veriagent.ingest")
public class IngestProperties {
    /** Activa el sondeo del buzón con Camel. */
    private boolean pollerEnabled = true;
    /**
     * Origen de los archivos. Perfil local: carpeta del SFTP simulado. Perfil full: un URI {@code sftp://...}
     * de Camel con autenticación por llave (sin contraseñas en el repositorio).
     */
    private String sourceUri;
    /** Carpeta del buzón (relativa a {@code veriagent.home}) para el perfil local y la carga por API. */
    private String inboxDir = "results/runtime/sftp/inbox";
    private long pollDelayMs = 500;
    /** Número máximo de entradas de un ZIP. */
    private int maxZipEntries = 1000;

    public boolean isPollerEnabled() { return pollerEnabled; }
    public void setPollerEnabled(boolean pollerEnabled) { this.pollerEnabled = pollerEnabled; }
    public String getSourceUri() { return sourceUri; }
    public void setSourceUri(String sourceUri) { this.sourceUri = sourceUri; }
    public String getInboxDir() { return inboxDir; }
    public void setInboxDir(String inboxDir) { this.inboxDir = inboxDir; }
    public long getPollDelayMs() { return pollDelayMs; }
    public void setPollDelayMs(long pollDelayMs) { this.pollDelayMs = pollDelayMs; }
    public int getMaxZipEntries() { return maxZipEntries; }
    public void setMaxZipEntries(int maxZipEntries) { this.maxZipEntries = maxZipEntries; }
}
