package org.veriagent.core.state;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Ciclo de vida de un comprobante (apartado 2.2 del documento) con la cuarentena en DLQ:
 * {@code RECEIVED → PENDING_BATCH → SENT → AUTHORIZED | FAILED}; en el canal síncrono, {@code RECEIVED → SENT}.
 * Un comprobante bloqueado por el kit pasa a {@code FAILED} sin salir hacia el proveedor. Desde {@code DLQ}
 * solo se sale por un reproceso autorizado, que vuelve a {@code RECEIVED}.
 */
public enum InvoiceState {
    RECEIVED, PENDING_BATCH, SENT, AUTHORIZED, FAILED, DLQ;

    private static final Map<InvoiceState, Set<InvoiceState>> ALLOWED = Map.of(
        RECEIVED, EnumSet.of(PENDING_BATCH, SENT, FAILED, DLQ),
        PENDING_BATCH, EnumSet.of(SENT, FAILED, DLQ),
        SENT, EnumSet.of(AUTHORIZED, FAILED, DLQ),
        FAILED, EnumSet.of(DLQ),
        DLQ, EnumSet.of(RECEIVED),
        AUTHORIZED, EnumSet.noneOf(InvoiceState.class));

    public boolean canMoveTo(InvoiceState next) {
        return ALLOWED.get(this).contains(next);
    }

    public Set<InvoiceState> next() {
        return Set.copyOf(ALLOWED.get(this));
    }

    /** Estados en los que el comprobante ya no avanza sin intervención. */
    public boolean isTerminal() {
        return this == AUTHORIZED || this == FAILED || this == DLQ;
    }
}
