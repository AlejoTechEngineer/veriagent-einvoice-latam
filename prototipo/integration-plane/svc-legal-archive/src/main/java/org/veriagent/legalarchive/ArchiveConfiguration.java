package org.veriagent.legalarchive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.veriagent.core.VeriagentProperties;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.ledger.InvoiceLedger;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Configuración y API (solo lectura) del archivo legal. No existen operaciones de modificación ni de borrado. */
@Configuration
@EnableConfigurationProperties(ArchiveConfiguration.ArchiveProperties.class)
@ConditionalOnProperty(prefix = "veriagent.services", name = "legal-archive", havingValue = "true", matchIfMissing = true)
public class ArchiveConfiguration {

    /** Prefijo {@code veriagent.archive}. Retención en años por país (supuesto S-15; la fija cada país). */
    @ConfigurationProperties("veriagent.archive")
    public static class ArchiveProperties {
        private String root = "results/runtime/archive";
        private final Map<String, Integer> retentionYears = new LinkedHashMap<>(Map.of("CO", 5, "PE", 5, "XX", 7));

        public String getRoot() { return root; }
        public void setRoot(String root) { this.root = root; }
        public Map<String, Integer> getRetentionYears() { return retentionYears; }
    }

    @Bean
    public LegalArchive legalArchive(EventBus bus, InvoiceLedger ledger, JdbcClient jdbc, VeriagentProperties core, ArchiveProperties props) {
        Path root = Path.of(core.getHome()).resolve(props.getRoot()).toAbsolutePath().normalize();
        return new LegalArchive(bus, ledger, jdbc, root, props.getRetentionYears());
    }

    @RestController
    @RequestMapping("/api/archive")
    @Tag(name = "Archivo legal", description = "Solo anexado, con cadena de huellas SHA-256 (ADR-07)")
    @ConditionalOnProperty(prefix = "veriagent.services", name = "legal-archive", havingValue = "true", matchIfMissing = true)
    public static class ArchiveController {
        private final LegalArchive archive;

        public ArchiveController(LegalArchive archive) {
            this.archive = archive;
        }

        @Operation(summary = "Últimas entradas del archivo")
        @GetMapping
        public Map<String, Object> list(@RequestParam(defaultValue = "50") int limit) {
            List<LegalArchive.Entry> entries = archive.list(Math.max(1, Math.min(limit, 500)));
            return Map.of("entries", entries, "pending", archive.pendingCount());
        }

        @Operation(summary = "Verifica la cadena de huellas y la integridad de cada objeto")
        @GetMapping("/verify")
        public LegalArchive.Verification verify() {
            return archive.verify();
        }
    }
}
