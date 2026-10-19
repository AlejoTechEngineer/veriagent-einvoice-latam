package org.veriagent.core.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Toma (o crea) el identificador de correlación de cada petición y lo devuelve en la respuesta. */
public class CorrelationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String id = CorrelationContext.sanitize(request.getHeader(CorrelationContext.HEADER));
        response.setHeader(CorrelationContext.HEADER, id);
        CorrelationContext.set(id);
        try {
            chain.doFilter(request, response);
        } finally {
            CorrelationContext.clear();
        }
    }
}
