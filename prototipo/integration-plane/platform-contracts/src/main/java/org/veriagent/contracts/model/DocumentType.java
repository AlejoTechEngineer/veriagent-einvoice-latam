package org.veriagent.contracts.model;

/** Tipo de comprobante del modelo canónico. Las notas exigen el número del documento original. */
public enum DocumentType {
    INVOICE, CREDIT_NOTE, DEBIT_NOTE;

    public boolean isNote() { return this != INVOICE; }
}
