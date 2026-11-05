package org.ohia.facade.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    /** For the container health check. Caddy does not route /internal/**, so it is not public. */
    @GetMapping("/internal/health")
    public Map<String, String> internalHealth() {
        return Map.of("status", "ok");
    }

    /** Reachable through Caddy and the S1 token; confirms the routing and the token lock. */
    @GetMapping("/ode/health")
    public Map<String, String> odeHealth() {
        return Map.of("status", "ok", "service", "ohia-facade");
    }
}
