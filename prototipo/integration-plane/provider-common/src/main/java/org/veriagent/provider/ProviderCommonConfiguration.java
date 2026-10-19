package org.veriagent.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.veriagent.contracts.ContractJson;
import org.veriagent.core.VeriagentProperties;
import org.veriagent.kit.KitVerifier;
import org.veriagent.provider.client.ProviderClient;
import org.veriagent.provider.client.ProvidersProperties;
import org.veriagent.provider.mapping.InMemoryMappingProvider;
import org.veriagent.provider.mapping.MappingDocument;
import org.veriagent.provider.mapping.MappingProvider;
import org.veriagent.provider.mapping.Renderers;
import org.veriagent.provider.routing.RoutingTable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Beans compartidos por adaptadores, conectores, enrutador y fachada. */
@Configuration
@EnableConfigurationProperties(ProvidersProperties.class)
public class ProviderCommonConfiguration {
    private static final Logger log = LoggerFactory.getLogger(ProviderCommonConfiguration.class);

    static Path home(VeriagentProperties props) {
        return Path.of(props.getHome()).toAbsolutePath().normalize();
    }

    @Bean
    public Renderers renderers() {
        return new Renderers();
    }

    @Bean
    public RoutingTable routingTable(VeriagentProperties props, ProvidersProperties providers) {
        RoutingTable t = RoutingTable.load(home(props).resolve(providers.getRoutingTable()));
        log.info("Tabla de enrutamiento v{} ({} reglas, sha256 {})", t.version(), t.rules().size(), t.sha256().substring(0, 12));
        return t;
    }

    /**
     * Mapeos sembrados en memoria desde los archivos aprobados ({@code veriagent.mappings.source=seed}, valor por
     * defecto en las pruebas de cada servicio). Con {@code registry} lo sustituye el registro de mapeos persistente.
     */
    @Bean
    @ConditionalOnProperty(name = "veriagent.mappings.source", havingValue = "seed", matchIfMissing = true)
    public MappingProvider seededMappingProvider(VeriagentProperties props, ProvidersProperties providers) {
        InMemoryMappingProvider p = new InMemoryMappingProvider();
        providers.getSeedMappings().forEach((key, file) -> {
            String[] k = key.split("/");
            try {
                var body = ContractJson.mapper().readTree(Files.readString(home(props).resolve(file)));
                p.publishAndActivate(MappingDocument.of(k[0], k[1], 1, body));
            } catch (IOException e) {
                throw new UncheckedIOException("No se pudo cargar el mapeo sembrado " + file, e);
            }
        });
        return p;
    }

    @Bean
    public DocumentPreparer documentPreparer(MappingProvider mappings, Renderers renderers, KitVerifier kit) {
        return new DocumentPreparer(mappings, renderers, kit);
    }

    @Bean
    public ProviderClient providerClient(VeriagentProperties props, ProvidersProperties providers) {
        return new ProviderClient(providers, home(props).resolve(providers.getSftpRoot()));
    }
}
