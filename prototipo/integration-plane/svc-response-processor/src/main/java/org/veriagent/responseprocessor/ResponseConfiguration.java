package org.veriagent.responseprocessor;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.veriagent.core.VeriagentProperties;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.bus.FlowEvents;
import org.veriagent.core.ledger.InvoiceLedger;
import org.veriagent.provider.client.ProvidersProperties;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Configuración del procesamiento de respuestas: sondeo de la carpeta de retorno con Camel y conciliación periódica. */
@Configuration
@EnableConfigurationProperties(ResponseProperties.class)
@ConditionalOnProperty(prefix = "veriagent.services", name = "response-processor", havingValue = "true", matchIfMissing = true)
public class ResponseConfiguration {

    static Path returnRoot(VeriagentProperties core, ProvidersProperties providers) {
        return Path.of(core.getHome()).resolve(providers.getSftpRoot()).resolve("return").toAbsolutePath().normalize();
    }

    @Bean
    public ResponseProcessor responseProcessor(EventBus bus, InvoiceLedger ledger, FlowEvents flow, VeriagentProperties core,
                                               ProvidersProperties providers, ResponseProperties props) {
        return new ResponseProcessor(bus, ledger, flow, returnRoot(core, providers), props);
    }

    @Bean
    @ConditionalOnProperty(name = "veriagent.responses.poller-enabled", havingValue = "true", matchIfMissing = true)
    public RouteBuilder returnFolderRoute(ResponseProcessor processor, VeriagentProperties core, ProvidersProperties providers,
                                          ResponseProperties props) {
        String uri = props.getSourceUri() != null && !props.getSourceUri().isBlank()
            ? props.getSourceUri()
            : "file:" + returnRoot(core, providers).toString().replace('\\', '/')
                + "?recursive=true&include=.*\\.result\\.json&readLock=changed&readLockMinAge=100"
                + "&move=.procesados/${file:name}&moveFailed=.rechazados/${file:name}&delay=" + props.getPollDelayMs();
        return new RouteBuilder() {
            @Override
            public void configure() {
                from(uri).routeId("retorno-proveedores")
                    .process(ex -> processor.processResultFile(new String(ex.getIn().getBody(byte[].class), StandardCharsets.UTF_8)));
            }
        };
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService reconciliationScheduler(ResponseProcessor processor, ResponseProperties props) {
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "conciliacion");
            t.setDaemon(true);
            return t;
        });
        long every = props.getReconcileInterval().toMillis();
        if (every > 0) s.scheduleWithFixedDelay(() -> {
            try {
                processor.reconcile();
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(ResponseConfiguration.class).warn("Conciliación fallida: {}", e.toString());
            }
        }, every, every, TimeUnit.MILLISECONDS);
        return s;
    }
}
