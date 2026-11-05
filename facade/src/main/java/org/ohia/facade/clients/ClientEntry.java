package org.ohia.facade.clients;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * One registered tester.
 *
 * @param clientId               the OAuth client id (also the id used when asking the facade to send to them)
 * @param requestingOrganization the FHIR reference of the organization the tester plays, for example
 *                               Organization/org-restorative-dental-tx; ARex retrieval is checked against it
 * @param allowedTargetPrefixes  URL prefixes the facade may send this tester's referrals to
 * @param jwks                   the tester's public keys (a JWK Set), used to verify their client assertions
 */
public record ClientEntry(String clientId, String requestingOrganization, List<String> allowedTargetPrefixes,
        JsonNode jwks) {
}
