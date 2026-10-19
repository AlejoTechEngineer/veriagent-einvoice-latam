package org.veriagent.core.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Identidad de quien ejecuta la operación actual. Fuera de una petición autenticada es {@link Actor#SYSTEM}. */
public final class CurrentActor {
    private CurrentActor() {}

    public static Actor get() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.isAuthenticated() && a.getPrincipal() instanceof Actor actor) return actor;
        return Actor.SYSTEM;
    }
}
