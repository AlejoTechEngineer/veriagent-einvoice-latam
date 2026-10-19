package org.veriagent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.veriagent.core.audit.AuditService;
import org.veriagent.core.bus.EventBus;
import org.veriagent.core.bus.InMemoryEventBus;
import org.veriagent.core.bus.KafkaEventBus;
import org.veriagent.core.correlation.CorrelationFilter;
import org.veriagent.core.dlq.DlqRepository;
import org.veriagent.core.bus.FlowEvents;
import org.veriagent.core.ledger.InvoiceLedger;
import org.veriagent.core.retry.RetryPolicies;

/** Beans del núcleo compartido. Se registra por escaneo de componentes del paquete {@code org.veriagent}. */
@Configuration
@EnableConfigurationProperties(VeriagentProperties.class)
public class CoreConfiguration {
    private static final Logger log = LoggerFactory.getLogger(CoreConfiguration.class);

    @Bean
    public InvoiceLedger invoiceLedger(JdbcClient jdbc) {
        return new InvoiceLedger(jdbc);
    }

    @Bean
    public AuditService auditService(JdbcClient jdbc) {
        return new AuditService(jdbc);
    }

    @Bean
    public RetryPolicies retryPolicies(VeriagentProperties props) {
        return new RetryPolicies(props);
    }

    @Bean
    public DlqRepository dlqRepository(JdbcClient jdbc) {
        return new DlqRepository(jdbc);
    }

    @Bean
    public FlowEvents flowEvents(EventBus bus) {
        return new FlowEvents(bus);
    }

    @Bean
    public FilterRegistrationBean<CorrelationFilter> correlationFilter() {
        FilterRegistrationBean<CorrelationFilter> r = new FilterRegistrationBean<>(new CorrelationFilter());
        r.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return r;
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "veriagent.bus.type", havingValue = "memory", matchIfMissing = true)
    public InMemoryEventBus inMemoryEventBus(VeriagentProperties props) {
        log.info("Bus de eventos en memoria (perfil local)");
        return new InMemoryEventBus(props.getBus().isValidateContracts());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "veriagent.bus.type", havingValue = "kafka")
    public EventBus kafkaEventBus(VeriagentProperties props) {
        log.info("Bus de eventos Kafka en {}", props.getBus().getBootstrapServers());
        return new KafkaEventBus(props.getBus().getBootstrapServers(), props.getBus().isValidateContracts());
    }
}
