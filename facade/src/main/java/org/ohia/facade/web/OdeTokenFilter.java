package org.ohia.facade.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.ohia.facade.config.FacadeProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Protects /ode/** with the shared S1 bearer token, the same secret Caddy enforces on /fhir.
 * Caddy already checks it before routing here; this is a second lock so the facade stays
 * closed even if it is ever reached without Caddy. If no token is configured, everything
 * under /ode/ is refused (fail closed). It also guards /fhir/ExplanationOfBenefit, which the facade
 * serves itself. The /arex/** paths are NOT covered: they use their own OAuth.
 */
@Component
public class OdeTokenFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";
    private final byte[] expected;

    public OdeTokenFilter(FacadeProperties props) {
        String token = props.apiToken();
        this.expected = (token == null || token.isBlank()) ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    /** The oral EOBs sit at the FHIR base, where the shared token is the rule, so this filter guards them too. */
    private static final String EOB = "/fhir/ExplanationOfBenefit";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean ode = path.equals("/ode") || path.startsWith("/ode/");
        boolean eob = path.equals(EOB) || path.startsWith(EOB + "/");
        return !(ode || eob);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (expected != null && header != null && header.startsWith(PREFIX)) {
            byte[] presented = header.substring(PREFIX.length()).getBytes(StandardCharsets.UTF_8);
            if (MessageDigest.isEqual(expected, presented)) {
                chain.doFilter(request, response);
                return;
            }
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("text/plain");
        response.getWriter().write("Unauthorized: send Authorization: Bearer <token>");
    }
}
