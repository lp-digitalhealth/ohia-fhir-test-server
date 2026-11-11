package org.ohia.facade.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the payer-side resources for the patient-app FHIR base come from. Bound from {@code facade.patient-access}.
 *
 * @param dataBaseUrl base URL that serves raw files of the data repository
 * @param dataRef     the pinned commit of the data repository (never a moving branch)
 * @param files       paths of the payer-side files inside the data repository, each fetched at the pinned commit
 */
@ConfigurationProperties(prefix = "facade.patient-access")
public record PatientAccessProperties(String dataBaseUrl, String dataRef, List<String> files) {

    public PatientAccessProperties {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
