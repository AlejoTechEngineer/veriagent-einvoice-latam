package org.veriagent.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Aplicación Spring Boot única y delgada (desviación D-03): escanea {@code org.veriagent} y cada servicio se activa
 * con {@code veriagent.services.<nombre>}. En el perfil local corren todos en un proceso; en el perfil full la
 * misma imagen se ejecuta varias veces con subconjuntos distintos.
 */
@SpringBootApplication(scanBasePackages = "org.veriagent")
public class VeriagentPlatformApplication {
    public static void main(String[] args) {
        SpringApplication.run(VeriagentPlatformApplication.class, args);
    }
}
