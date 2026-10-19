package org.veriagent.responseprocessor;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Configuración del procesamiento de respuestas (prefijo {@code veriagent.responses}). */
@ConfigurationProperties("veriagent.responses")
public class ResponseProperties {
    private boolean pollerEnabled = true;
    /** Origen de los resultados; si está vacío se usa la carpeta de retorno del SFTP simulado. */
    private String sourceUri;
    private long pollDelayMs = 300;
    /** Tiempo tras el envío a partir del cual se busca la constancia oficial. */
    private Duration receiptGrace = Duration.ofSeconds(20);
    /** Tiempo máximo sin respuesta ni constancia antes de llevar el comprobante a la DLQ. */
    private Duration responseTimeout = Duration.ofMinutes(5);
    /** Cada cuánto se ejecuta la conciliación automática; 0 la desactiva. */
    private Duration reconcileInterval = Duration.ofSeconds(30);

    public boolean isPollerEnabled() { return pollerEnabled; }
    public void setPollerEnabled(boolean pollerEnabled) { this.pollerEnabled = pollerEnabled; }
    public String getSourceUri() { return sourceUri; }
    public void setSourceUri(String sourceUri) { this.sourceUri = sourceUri; }
    public long getPollDelayMs() { return pollDelayMs; }
    public void setPollDelayMs(long pollDelayMs) { this.pollDelayMs = pollDelayMs; }
    public Duration getReceiptGrace() { return receiptGrace; }
    public void setReceiptGrace(Duration receiptGrace) { this.receiptGrace = receiptGrace; }
    public Duration getResponseTimeout() { return responseTimeout; }
    public void setResponseTimeout(Duration responseTimeout) { this.responseTimeout = responseTimeout; }
    public Duration getReconcileInterval() { return reconcileInterval; }
    public void setReconcileInterval(Duration reconcileInterval) { this.reconcileInterval = reconcileInterval; }
}
