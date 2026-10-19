package org.veriagent.provider.mapping;

import java.util.List;
import java.util.Optional;

/**
 * Fuente del mapeo vigente por proveedor y país. Los adaptadores y conectores la consultan en cada documento,
 * de modo que una versión activada (o revertida) se aplica en caliente sin reiniciar.
 */
public interface MappingProvider {
    Optional<MappingDocument> current(String provider, String country);

    /** Todas las versiones conocidas (la vigente incluida), de la más antigua a la más reciente. */
    List<MappingDocument> versions(String provider, String country);
}
