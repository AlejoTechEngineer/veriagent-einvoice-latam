package org.veriagent.contracts.model;

/** Emisor o adquirente del comprobante. El correo solo es obligatorio para el adquirente en algunos perfiles. */
public record Party(String name, String taxId, IdType idType, String email) {
}
