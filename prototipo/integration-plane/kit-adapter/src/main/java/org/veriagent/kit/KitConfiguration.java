package org.veriagent.kit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.tfg.kit.VerificationKit;
import org.veriagent.core.VeriagentProperties;

import java.nio.file.Path;

/** El kit se carga una vez desde la raíz del prototipo (specs/, catalogs/ y schemas/). */
@Configuration
public class KitConfiguration {

    @Bean
    public VerificationKit verificationKit(VeriagentProperties props) throws Exception {
        return new VerificationKit(Path.of(props.getHome()).toAbsolutePath().normalize());
    }

    @Bean
    public KitVerifier kitVerifier(VerificationKit kit) {
        return new KitVerifier(kit);
    }
}
