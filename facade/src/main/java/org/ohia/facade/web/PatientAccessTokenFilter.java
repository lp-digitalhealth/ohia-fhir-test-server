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
 * Protects the patient-app FHIR base {@code /patient-access/fhir/**} with the shared bearer token, the interim rule of this
 * server until a SMART authorization server exists. Caddy already checks it; this is a second lock so the facade stays
 * closed if it is ever reached without Caddy. If no token is configured, everything but the capability statement is refused
 * (fail closed). Two things stay open on purpose: {@code GET/HEAD .../metadata} (a client must be able to discover the server
 * before it has credentials) and {@code OPTIONS} (browser CORS preflights never carry an Authorization header).
 *
 * <p>The refusal is a 401 with a Bearer challenge and an OperationOutcome, the form a FHIR client expects.
 */
@Component
public class PatientAccessTokenFilter extends OncePerRequestFilter {

    private static final String BASE = "/patient-access/fhir";
    private static final String PREFIX = "Bearer ";
    private final byte[] expected;

    public PatientAccessTokenFilter(FacadeProperties props) {
        String token = props.apiToken();
        this.expected = (token == null || token.isBlank()) ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!(path.equals(BASE) || path.startsWith(BASE + "/"))) {
            return true;
        }
        String method = request.getMethod();
        boolean metadataRead = path.equals(BASE + "/metadata") && (method.equals("GET") || method.equals("HEAD"));
        return metadataRead || method.equals("OPTIONS");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
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
        response.setContentType("application/fhir+json");
        response.getWriter().write("{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"login\","
                + "\"details\":{\"text\":\"Unauthorized: send Authorization: Bearer <token>.\"}}]}");
    }
}
