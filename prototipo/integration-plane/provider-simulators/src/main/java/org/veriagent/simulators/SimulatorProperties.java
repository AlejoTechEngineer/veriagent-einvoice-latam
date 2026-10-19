package org.veriagent.simulators;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("veriagent.simulators")
public class SimulatorProperties {
    /** Demora del fallo SLOW: debe superar el tiempo de espera del cliente. */
    private Duration slowDelay = Duration.ofSeconds(6);
    /** Cada cuánto revisa Beta su carpeta SFTP de entrada. */
    private Duration pollDelay = Duration.ofMillis(300);

    public Duration getSlowDelay() { return slowDelay; }
    public void setSlowDelay(Duration slowDelay) { this.slowDelay = slowDelay; }
    public Duration getPollDelay() { return pollDelay; }
    public void setPollDelay(Duration pollDelay) { this.pollDelay = pollDelay; }
}
