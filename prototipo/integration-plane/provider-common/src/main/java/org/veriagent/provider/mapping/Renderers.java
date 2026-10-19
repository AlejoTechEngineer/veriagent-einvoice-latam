package org.veriagent.provider.mapping;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Registro de transformadores por perfil de mapeo. */
public class Renderers {
    private final Map<String, Renderer> byProfile = new LinkedHashMap<>();

    public Renderers() {
        register(new AlfaXmlRenderer());
        register(new BetaSegmentRenderer());
        register(new BetaSegmentRenderer("beta-pe-segmentos", "beta-pe"));
        register(new GammaJsonRenderer());
    }

    public final void register(Renderer r) {
        byProfile.put(r.mappingProfile(), r);
    }

    public Optional<Renderer> forProfile(String mappingProfile) {
        return Optional.ofNullable(byProfile.get(mappingProfile));
    }
}
