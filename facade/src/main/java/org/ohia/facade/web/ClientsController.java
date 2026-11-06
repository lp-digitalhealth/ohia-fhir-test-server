package org.ohia.facade.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.ohia.facade.clients.ClientRegistry;
import org.ohia.facade.common.UseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The registered testers, for the send form. Names, organizations and allowed targets only; never their keys. */
@RestController
public class ClientsController {

    private final ClientRegistry registry;

    public ClientsController(ClientRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/ode/clients")
    public List<Map<String, Object>> clients() {
        return registry.all().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clientId", c.clientId());
            Map<String, String> orgs = new LinkedHashMap<>();
            for (UseCase u : UseCase.values()) {
                orgs.put(u.id(), c.organizationFor(u));
            }
            m.put("organizations", orgs);
            m.put("allowedTargetPrefixes", c.allowedTargetPrefixes());
            return m;
        }).toList();
    }
}
