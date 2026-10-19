package org.veriagent.core.testsupport;

import org.veriagent.core.security.Actor;
import org.veriagent.core.security.DemoTokenStore;
import org.veriagent.core.security.Role;

/** Emite tokens de prueba en el almacén de la aplicación bajo prueba. */
public final class TestTokens {
    private final DemoTokenStore store;

    public TestTokens(DemoTokenStore store) {
        this.store = store;
    }

    public String bearer(String subject, Role role) {
        return "Bearer " + store.issue(new Actor(subject, role));
    }

    public String reviewer() { return bearer("revisor.prueba", Role.REVIEWER); }
    public String agent() { return bearer("agente.prueba", Role.AGENT); }
    public String operator() { return bearer("operador.prueba", Role.OPERATOR); }
    public String system() { return bearer("sistema.prueba", Role.SYSTEM); }
}
