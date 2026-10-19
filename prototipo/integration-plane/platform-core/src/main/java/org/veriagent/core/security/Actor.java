package org.veriagent.core.security;

/** Identidad autenticada: sujeto y rol, tomados siempre del token, nunca del cuerpo de la petición. */
public record Actor(String subject, Role role) {
    /** Identidad de los procesos internos (consumidores de eventos, tareas programadas). */
    public static final Actor SYSTEM = new Actor("sistema", Role.SYSTEM);
}
