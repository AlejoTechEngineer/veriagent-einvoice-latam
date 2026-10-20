package org.veriagent.mappingregistry;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.veriagent.contracts.ContractJson;
import org.veriagent.contracts.events.Payloads;
import org.veriagent.contracts.events.Topics;
import org.veriagent.core.VeriagentProperties;
import org.veriagent.core.audit.AuditService;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.bus.Events;
import org.veriagent.core.correlation.CorrelationContext;
import org.veriagent.kit.KitVerifier;
import org.veriagent.provider.client.ProvidersProperties;
import org.veriagent.provider.mapping.Renderers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Registro de mapeos como fuente de los mapeos vigentes ({@code veriagent.mappings.source=registry}). Siembra la
 * línea base aprobada y escucha {@code platform.mapping.activated.v1} para recargar en caliente (en el perfil full,
 * cada proceso tiene su propio grupo de consumidores y recibe todas las activaciones).
 */
@Configuration
@ConditionalOnProperty(name = "veriagent.mappings.source", havingValue = "registry")
public class MappingRegistryConfiguration {

    @Bean
    public MappingGate mappingGate(KitVerifier kit, Renderers renderers, VeriagentProperties core) {
        return new MappingGate(kit, renderers, Path.of(core.getHome()).toAbsolutePath().normalize());
    }

    @Bean
    public MappingRegistry mappingRegistry(JdbcClient jdbc, MappingGate gate, EventBus bus, AuditService audit) {
        return new MappingRegistry(jdbc, gate, bus, audit);
    }

    @Bean
    public ReviewAudit reviewAudit(JdbcClient jdbc, AuditService audit,
                                   @Value("${veriagent.review-audit.rate:0.10}") double rate,
                                   @Value("${veriagent.review-audit.seed:}") String seed) {
        return new ReviewAudit(jdbc, audit, rate, seed == null || seed.isBlank() ? null : Long.valueOf(seed.trim()));
    }

    @Bean
    public Seeder mappingSeeder(MappingRegistry registry, EventBus bus, VeriagentProperties core, ProvidersProperties providers) {
        return new Seeder(registry, bus, core, providers);
    }

    /** Siembra al arrancar y suscribe la recarga en caliente. */
    public static class Seeder {
        private final MappingRegistry registry;
        private final EventBus bus;
        private final VeriagentProperties core;
        private final ProvidersProperties providers;

        Seeder(MappingRegistry registry, EventBus bus, VeriagentProperties core, ProvidersProperties providers) {
            this.registry = registry;
            this.bus = bus;
            this.core = core;
            this.providers = providers;
        }

        @PostConstruct
        void start() {
            Path home = Path.of(core.getHome()).toAbsolutePath().normalize();
            CorrelationContext.run("arranque-registro-mapeos", () -> providers.getSeedMappings().forEach((k, file) -> {
                String[] pc = k.split("/");
                try {
                    registry.seed(pc[0], pc[1], ContractJson.mapper().readTree(Files.readString(home.resolve(file))));
                } catch (IOException e) {
                    throw new UncheckedIOException("No se pudo sembrar el mapeo " + file, e);
                }
            }));
            registry.refreshAll();
            bus.subscribe("^" + Topics.MAPPING_ACTIVATED.replace(".", "\\.") + "$", "mapping-reload-" + UUID.randomUUID(), (t, e) -> {
                var a = Events.payload(e, Payloads.MappingActivated.class);
                registry.refresh(a.provider(), a.country());
            });
        }
    }
}
