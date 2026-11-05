package org.ohia.facade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings, bound from application.yml and environment variables.
 *
 * @param hapiBaseUrl   FHIR base of the HAPI server on the compose network, e.g. http://hapi:8080/fhir
 * @param publicBaseUrl public base URL of this deployment with no trailing slash and no path,
 *                      e.g. https://example.org (used to build absolute ARex content URLs)
 * @param apiToken      the shared S1 bearer token that protects /ode/**; blank means deny everything
 * @param dataDir       directory for the facade's own files (payloads, package index, manifests, logs)
 * @param clientsFile   the tester registry (JSON); a missing file means no tester is registered
 * @param seed          where the UC07 data comes from
 * @param send          outbound sending limits
 * @param client        how this server behaves as an ARex client (content consumer)
 */
@ConfigurationProperties(prefix = "facade")
public record FacadeProperties(String hapiBaseUrl, String publicBaseUrl, String apiToken, String dataDir,
        String clientsFile, Seed seed, Send send, Client client) {

    /**
     * @param dataBaseUrl base URL that serves raw files of the data repository
     * @param dataRef     the pinned commit of the data repository (never a moving branch)
     * @param packagePath folder inside the data repository that holds the interaction-01 files
     */
    public record Seed(String dataBaseUrl, String dataRef, String packagePath) {
    }

    /**
     * @param allowHttp           TEST ONLY. Permit http:// targets. Default false.
     * @param allowPrivateTargets TEST ONLY. Permit targets that resolve to loopback, private or
     *                            link-local addresses. Default false; never set this in production.
     * @param manifestTtlDays     how long a sent ARex manifest (and its images) stays retrievable
     */
    public record Send(boolean allowHttp, boolean allowPrivateTargets, int manifestTtlDays) {
    }

    /**
     * @param id                       our OAuth client id, as registered at a sender's ARex server
     * @param keyFile                  where our own key pair is kept (created on first use if absent)
     * @param requestingOrganization   the organization we play when we retrieve (UC04a: Barton Springs)
     * @param authorizationDetailsType the RFC 9396 type we ask for; the draft ARex spec names none
     * @param maxObjects               most content objects we will pull for one receipt
     * @param maxObjectBytes           largest single object we will accept
     * @param maxTotalBytes            most bytes we will pull for one receipt
     * @param connectTimeoutSeconds    connect timeout for every call to a sender
     * @param readTimeoutSeconds       per-request timeout for every call to a sender
     */
    public record Client(String id, String keyFile, String requestingOrganization, String authorizationDetailsType,
            int maxObjects, long maxObjectBytes, long maxTotalBytes, int connectTimeoutSeconds, int readTimeoutSeconds) {
    }
}
