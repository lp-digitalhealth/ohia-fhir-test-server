package org.ohia.facade.clients;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.ohia.facade.common.UseCase;

/**
 * One registered tester.
 *
 * @param clientId               the OAuth client id (also the id used when asking the facade to send to them)
 * @param requestingOrganization the FHIR reference of the organization the tester plays, for example
 *                               Organization/org-restorative-dental-tx; ARex retrieval is checked against it
 * @param allowedTargetPrefixes  URL prefixes the facade may send this tester's referrals to
 * @param jwks                   the tester's public keys (a JWK Set), used to verify their client assertions
 * @param organizationsByUseCase optional: the organization the tester plays in a use case where it differs from
 *                               {@code requestingOrganization} (UC04a: the practice, Organization/org-barton-springs-dental)
 */
public record ClientEntry(String clientId, String requestingOrganization, List<String> allowedTargetPrefixes,
        JsonNode jwks, Map<String, String> organizationsByUseCase) {

    public ClientEntry(String clientId, String requestingOrganization, List<String> allowedTargetPrefixes, JsonNode jwks) {
        this(clientId, requestingOrganization, allowedTargetPrefixes, jwks, Map.of());
    }

    /** The organization this tester plays when we send it this use case's referral. */
    public String organizationFor(UseCase useCase) {
        String own = organizationsByUseCase == null ? null : organizationsByUseCase.get(useCase.id());
        return own != null ? own : requestingOrganization;
    }

    /** May this tester retrieve a manifest that names this requesting organization? Any organization it plays counts. */
    public boolean plays(String organization) {
        if (requestingOrganization.equals(organization)) {
            return true;
        }
        return organizationsByUseCase != null && organizationsByUseCase.containsValue(organization);
    }
}
