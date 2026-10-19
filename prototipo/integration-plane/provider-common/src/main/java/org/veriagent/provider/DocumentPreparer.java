package org.veriagent.provider;

import org.tfg.kit.core.Mode;
import org.veriagent.contracts.model.InvoiceRequest;
import org.veriagent.kit.KitResult;
import org.veriagent.kit.KitVerifier;
import org.veriagent.provider.mapping.MappingDocument;
import org.veriagent.provider.mapping.MappingProvider;
import org.veriagent.provider.mapping.Renderer;
import org.veriagent.provider.mapping.Renderers;

/**
 * Prepara un comprobante para un proveedor: toma el mapeo vigente, lo transforma al formato del proveedor y lo
 * somete al kit en modo ejecución (ADR-05). Lo usan los adaptadores (antes de agrupar el lote) y los conectores
 * (antes de invocar al proveedor), así que una misma regla protege la emisión masiva y la unitaria.
 */
public class DocumentPreparer {
    private final MappingProvider mappings;
    private final Renderers renderers;
    private final KitVerifier kit;

    public DocumentPreparer(MappingProvider mappings, Renderers renderers, KitVerifier kit) {
        this.mappings = mappings;
        this.renderers = renderers;
        this.kit = kit;
    }

    /** Documento listo (o bloqueado por el kit), con la versión de mapeo que lo produjo. */
    public record Prepared(String provider, int mappingVersion, String mappingSha256, String kitProfile,
                           String content, KitResult verification) {
        public boolean passed() { return verification.passed(); }
    }

    public Prepared prepare(InvoiceRequest inv, String provider) {
        MappingDocument m = mappings.current(provider, inv.country().name())
            .orElseThrow(() -> new MissingMappingException(provider, inv.country().name()));
        Renderer r = renderers.forProfile(m.profile())
            .orElseThrow(() -> new IllegalStateException("No hay transformador para el perfil de mapeo " + m.profile()));
        String content = r.render(inv, m.body());
        KitResult v = kit.verify(r.kitProfile(), inv.invoiceKey(), content, Mode.RUNTIME, null);
        return new Prepared(provider, m.version(), m.sha256(), r.kitProfile(), content, v);
    }

    /** No hay mapeo vigente para el proveedor y el país: el comprobante no se puede transformar. */
    public static class MissingMappingException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public MissingMappingException(String provider, String country) {
            super("No hay mapeo vigente para el proveedor " + provider + " en " + country);
        }
    }
}
