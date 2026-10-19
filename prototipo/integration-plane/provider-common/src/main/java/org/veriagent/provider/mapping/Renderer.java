package org.veriagent.provider.mapping;

import org.veriagent.contracts.model.InvoiceRequest;
import tools.jackson.databind.JsonNode;

/** Transforma un comprobante canónico al formato de un proveedor según un mapeo. */
public interface Renderer {
    /** Perfil del mapeo que entiende (campo {@code profile} del documento de mapeo). */
    String mappingProfile();

    /** Perfil de verificación del kit para el documento resultante. */
    String kitProfile();

    String render(InvoiceRequest invoice, JsonNode mapping);
}
