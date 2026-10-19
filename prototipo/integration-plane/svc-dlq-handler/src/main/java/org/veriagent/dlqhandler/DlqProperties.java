package org.veriagent.dlqhandler;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Reintento controlado de la DLQ (prefijo {@code veriagent.dlq}). */
@ConfigurationProperties("veriagent.dlq")
public class DlqProperties {
    private boolean autoRetryEnabled = true;
    /** Máximo de reprocesos automáticos por comprobante (después solo reproceso manual). */
    private int autoRetryMax = 3;
    /** Espera base entre reprocesos automáticos; se duplica en cada intento. */
    private Duration autoRetryInterval = Duration.ofSeconds(30);

    public boolean isAutoRetryEnabled() { return autoRetryEnabled; }
    public void setAutoRetryEnabled(boolean autoRetryEnabled) { this.autoRetryEnabled = autoRetryEnabled; }
    public int getAutoRetryMax() { return autoRetryMax; }
    public void setAutoRetryMax(int autoRetryMax) { this.autoRetryMax = autoRetryMax; }
    public Duration getAutoRetryInterval() { return autoRetryInterval; }
    public void setAutoRetryInterval(Duration autoRetryInterval) { this.autoRetryInterval = autoRetryInterval; }
}
