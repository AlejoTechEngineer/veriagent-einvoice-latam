package org.veriagent.core.state;

/** Transición de estado no permitida o concurrente (otro proceso cambió el estado antes). */
public class IllegalTransitionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public IllegalTransitionException(String invoiceKey, InvoiceState from, InvoiceState to) {
        super("Transición no permitida para " + invoiceKey + ": " + from + " -> " + to);
    }
}
