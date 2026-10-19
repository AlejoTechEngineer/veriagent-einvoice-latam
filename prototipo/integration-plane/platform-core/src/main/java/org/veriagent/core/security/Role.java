package org.veriagent.core.security;

/**
 * Roles del prototipo (apartado 4.4.6, mínimo privilegio para la IA).
 * <ul>
 *   <li>REVIEWER: revisa y decide propuestas de IA; activa y revierte mapeos aprobados.</li>
 *   <li>AGENT: solo propone y consulta. Nunca aprueba, decide, activa mapeos ni reprocesa la DLQ.</li>
 *   <li>OPERATOR: opera la plataforma: emite, carga lotes, reprocesa la DLQ y controla los simuladores.</li>
 *   <li>SYSTEM: procesos internos entre servicios.</li>
 * </ul>
 */
public enum Role {
    REVIEWER, AGENT, OPERATOR, SYSTEM;

    /** Nombre de autoridad de Spring Security ({@code ROLE_REVIEWER}). */
    public String authority() { return "ROLE_" + name(); }
}
