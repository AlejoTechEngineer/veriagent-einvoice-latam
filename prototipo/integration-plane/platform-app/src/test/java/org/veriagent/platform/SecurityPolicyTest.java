package org.veriagent.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Política de seguridad sobre la aplicación completa: denegar por defecto. Toda operación que cambia estado
 * declara su regla de rol, y ninguna ruta de la API responde sin token.
 */
@SpringBootTest(properties = "veriagent.ingest.poller-enabled=false")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SecurityPolicyTest {
    /** Operaciones POST que no cambian estado: verificar un documento y previsualizar una ruta. */
    static final Set<String> READ_ONLY_POSTS = Set.of("KitController.verify", "RouterController.preview");
    /**
     * Recepción de los proveedores simulados (/sim/): representan sistemas externos. Sin token solo si el perfil
     * lo declara en simulator-public-paths (perfil local); en el resto exigen token como cualquier ruta.
     */
    static final Set<String> SIMULATED_PROVIDER_RECEIVERS = Set.of("SimulatorController.document", "SimulatorController.batch");

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping;
    @Autowired MockMvc mvc;

    private Map<RequestMappingInfo, HandlerMethod> apiHandlers() {
        Map<RequestMappingInfo, HandlerMethod> out = new java.util.LinkedHashMap<>();
        mapping.getHandlerMethods().forEach((info, h) -> {
            if (h.getBeanType().getPackageName().startsWith("org.veriagent")) out.put(info, h);
        });
        return out;
    }

    @Test
    @DisplayName("Toda operación que cambia estado declara @PreAuthorize (salvo las de solo lectura enumeradas)")
    void everyMutationHasRoleRule() {
        List<String> missing = new ArrayList<>();
        Set<String> checked = new TreeSet<>();
        apiHandlers().forEach((info, h) -> {
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            boolean mutating = methods.isEmpty() || methods.stream().anyMatch(m -> m != RequestMethod.GET);
            if (!mutating) return;
            String name = h.getBeanType().getSimpleName() + "." + h.getMethod().getName();
            checked.add(name);
            boolean rule = AnnotatedElementUtils.hasAnnotation(h.getMethod(), PreAuthorize.class)
                || AnnotatedElementUtils.hasAnnotation(h.getBeanType(), PreAuthorize.class);
            if (!rule && !READ_ONLY_POSTS.contains(name) && !SIMULATED_PROVIDER_RECEIVERS.contains(name)) missing.add(name + " " + info);
        });
        assertEquals(List.of(), missing);
        assertTrue(checked.containsAll(Set.of("InvoiceFacadeController.issue", "DlqController.reprocess", "DlqController.discard",
            "IngestController.upload", "MappingRegistryController.approve", "MappingRegistryController.activate",
            "SimulatorController.setFault", "DlqController.triage", "MappingRegistryController.check")), checked::toString);
    }

    @Test
    @DisplayName("Ninguna ruta GET de la API responde sin token (401)")
    void noAnonymousReads() throws Exception {
        List<String> open = new ArrayList<>();
        for (var e : apiHandlers().entrySet()) {
            if (!e.getKey().getMethodsCondition().getMethods().contains(RequestMethod.GET)) continue;
            for (String path : e.getKey().getPatternValues()) {
                if (!path.startsWith("/api/")) continue;
                String concrete = path.replaceAll("\\{[^}]+}", "1");
                int s = mvc.perform(get(concrete)).andReturn().getResponse().getStatus();
                if (s != 401) open.add(concrete + " -> " + s);
            }
        }
        assertEquals(List.of(), open);
    }
}
