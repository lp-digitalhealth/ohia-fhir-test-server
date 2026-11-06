package org.ohia.facade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings that only the second use case needs, kept apart from {@link FacadeProperties} so that
 * the UC07 settings are untouched.
 *
 * @param uc04a     where the UC04a interaction-1 package comes from (the data repository, at a pinned commit)
 * @param receiving the organization this server plays when it RECEIVES a UC07 referral
 */
@ConfigurationProperties(prefix = "facade.use-cases")
public record UseCaseProperties(Uc04a uc04a, Receiving receiving) {

    public static final String DEFAULT_UC04A_PATH =
            "2026-CMS-Connectathon/fhir-resources/purpose-built/uc04a-teledentistry-commercial/interactions/interaction-01";

    public UseCaseProperties {
        if (uc04a == null) {
            uc04a = new Uc04a(null, DEFAULT_UC04A_PATH);
        }
        if (receiving == null) {
            receiving = new Receiving("Organization/org-restorative-dental-tx");
        }
    }

    /** @param dataRef the pinned commit of the data repository; blank falls back to the UC07 one (tests only) */
    public record Uc04a(String dataRef, String packagePath) {
    }

    /** @param uc07RequestingOrganization the receiving specialist of UC07 (the registry example's organization) */
    public record Receiving(String uc07RequestingOrganization) {
    }
}
