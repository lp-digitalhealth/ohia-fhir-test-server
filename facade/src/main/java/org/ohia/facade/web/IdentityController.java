package org.ohia.facade.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.ohia.facade.config.FacadeProperties;
import org.ohia.facade.keys.ServerKeyStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the organizer hands to a sender so they can register this server as an ARex client:
 * our client id, our key id and algorithm, and where our public key is published.
 * Organizer-only (shared token): it is under /ode/**.
 */
@RestController
public class IdentityController {

    private final FacadeProperties props;
    private final ServerKeyStore keys;

    public IdentityController(FacadeProperties props, ServerKeyStore keys) {
        this.props = props;
        this.keys = keys;
    }

    @GetMapping("/ode/identity")
    public Map<String, Object> identity() {
        String base = props.publicBaseUrl().endsWith("/")
                ? props.publicBaseUrl().substring(0, props.publicBaseUrl().length() - 1) : props.publicBaseUrl();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("client_id", props.client().id());
        m.put("kid", keys.keyId());
        m.put("signing_algorithm", "RS384");
        m.put("jwks_uri", base + "/arex/jwks.json");
        m.put("requesting_organization", props.client().requestingOrganization());
        m.put("authorization_details_type", props.client().authorizationDetailsType());
        return m;
    }
}
