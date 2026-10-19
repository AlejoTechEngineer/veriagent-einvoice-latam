package org.veriagent.ingestsftp;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.veriagent.core.VeriagentProperties;
import org.veriagent.core.audit.AuditService;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.dlq.DlqRepository;
import org.veriagent.core.ledger.InvoiceLedger;

import java.nio.file.Path;

/**
 * Configuración de la ingesta. El sondeo del buzón usa Camel (su componente de archivo en el perfil local y
 * SFTP en el perfil full): es justo donde Camel aporta valor, con bloqueo de lectura y movimiento de lo procesado.
 */
@Configuration
@EnableConfigurationProperties(IngestProperties.class)
@ConditionalOnProperty(prefix = "veriagent.services", name = "ingest-sftp", havingValue = "true", matchIfMissing = true)
public class IngestConfiguration {

    @Bean
    public IngestService ingestService(InvoiceLedger ledger, DlqRepository dlq, EventBus bus, AuditService audit,
                                       JdbcClient jdbc, VeriagentProperties core, IngestProperties props) {
        return new IngestService(ledger, dlq, bus, audit, jdbc, core, props);
    }

    static Path inbox(VeriagentProperties core, IngestProperties props) {
        return Path.of(core.getHome()).resolve(props.getInboxDir()).toAbsolutePath().normalize();
    }

    @Bean
    @ConditionalOnProperty(name = "veriagent.ingest.poller-enabled", havingValue = "true", matchIfMissing = true)
    public RouteBuilder ingestRoute(IngestService service, VeriagentProperties core, IngestProperties props) {
        String uri = props.getSourceUri() != null && !props.getSourceUri().isBlank()
            ? props.getSourceUri()
            : "file:" + inbox(core, props).toString().replace('\\', '/')
                + "?include=.*\\.(json|jsonl|zip)&readLock=changed&readLockMinAge=200&move=.procesados/${file:name}"
                + "&moveFailed=.rechazados/${file:name}&delay=" + props.getPollDelayMs();
        return new RouteBuilder() {
            @Override
            public void configure() {
                from(uri).routeId("ingest-buzon")
                    .process(ex -> {
                        String name = ex.getIn().getHeader("CamelFileName", String.class);
                        byte[] body = ex.getIn().getBody(byte[].class);
                        service.ingest(name, body);
                    });
            }
        };
    }
}
