package org.veriagent.simulators;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.veriagent.core.VeriagentProperties;
import org.veriagent.kit.KitVerifier;
import org.veriagent.provider.client.ProvidersProperties;

import java.nio.file.Path;

/** Simuladores de proveedores: solo se activan explícitamente ({@code veriagent.services.provider-simulators=true}). */
@Configuration
@EnableConfigurationProperties(SimulatorProperties.class)
@ConditionalOnProperty(prefix = "veriagent.services", name = "provider-simulators", havingValue = "true")
public class SimulatorConfiguration {

    static Path sftpRoot(VeriagentProperties core, ProvidersProperties providers) {
        Path p = Path.of(providers.getSftpRoot());
        return p.isAbsolute() ? p : Path.of(core.getHome()).resolve(p).toAbsolutePath().normalize();
    }

    @Bean
    public ProviderSimulator providerSimulator(KitVerifier kit, VeriagentProperties core, ProvidersProperties providers) {
        return new ProviderSimulator(kit, new FaultInjector(), sftpRoot(core, providers).resolve("return"));
    }

    @Bean
    public BetaFolderPoller betaFolderPoller(ProviderSimulator sim, VeriagentProperties core, ProvidersProperties providers,
                                             SimulatorProperties props) {
        return new BetaFolderPoller(sim, sftpRoot(core, providers).resolve("outbound").resolve("beta").resolve("inbox"),
            props.getPollDelay(), core.getLimits());
    }
}
