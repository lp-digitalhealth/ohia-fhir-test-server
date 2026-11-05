package org.ohia.facade.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Strict browser-side protection for the viewer page under /viewer/**. The page shows content that a
 * sender controls, so it is allowed to run only its own script and style files (no inline script, no
 * inline style, no other origins), to talk only to this server, and to show images only from this
 * origin or from blob: URLs it creates itself. It cannot be framed, and nothing is cached.
 */
@Component
public class ViewerSecurityFilter extends OncePerRequestFilter {

    static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob:; "
            + "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/viewer") || path.startsWith("/viewer/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
