package org.veriagent.core.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.veriagent.core.VeriagentProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Seguridad del prototipo: denegar por defecto. Solo la salud, la documentación OpenAPI y los recursos
 * estáticos del front son públicos; cualquier otra ruta exige token, y las que cambian estado exigen además
 * el rol adecuado mediante {@code @PreAuthorize} en cada controlador.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    /** Rutas públicas: no exponen datos ni cambian estado. */
    public static final String[] PUBLIC_PATHS = {
        "/actuator/health", "/actuator/health/**", "/actuator/info",
        "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
        "/", "/index.html", "/favicon.ico", "/app/**", "/assets/**", "/error"
    };

    @Bean
    public DemoTokenStore demoTokenStore(VeriagentProperties props) {
        Path file = Path.of(props.getSecurity().getTokensFile());
        if (!file.isAbsolute()) file = Path.of(props.getHome()).resolve(file).normalize();
        if ("load".equals(props.getSecurity().getTokenMode()) && Files.exists(file)) {
            return DemoTokenStore.load(file);
        }
        return DemoTokenStore.generate(file, DemoTokenStore.DEFAULT_IDENTITIES);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, DemoTokenStore tokens) throws Exception {
        http
            .csrf(c -> c.disable())
            .httpBasic(b -> b.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable())
            .cors(c -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; "
                    + "frame-ancestors 'none'; base-uri 'self'; form-action 'self'")))
            .authorizeHttpRequests(a -> a
                .requestMatchers(PUBLIC_PATHS).permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) ->
                    SecurityResponses.write(res, 401, "UNAUTHENTICATED", "Falta el token de acceso"))
                .accessDeniedHandler((req, res, ex) ->
                    SecurityResponses.write(res, 403, "FORBIDDEN", "Tu rol no permite esta operación")))
            .addFilterBefore(new TokenAuthenticationFilter(tokens), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** CORS limitado al origen del front. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(VeriagentProperties props) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(props.getSecurity().getAllowedOrigins());
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id"));
        c.setExposedHeaders(List.of("X-Correlation-Id"));
        c.setAllowCredentials(false);
        c.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", c);
        return source;
    }

    /** Sin usuarios con contraseña: evita que Spring Boot cree un usuario por defecto. */
    @Bean
    public UserDetailsService noPasswordUsers() {
        return username -> { throw new UsernameNotFoundException("Sin usuarios con contraseña"); };
    }
}
