package org.ohia.facade.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the oral claims-sharing ExplanationOfBenefit files come from. Bound from {@code facade.eob}; kept apart from
 * {@link FacadeProperties} so adding it changes no existing constructor.
 *
 * @param dataBaseUrl base URL that serves raw files of the data repository
 * @param dataRef     the pinned commit of the data repository (never a moving branch)
 * @param files       paths of the EOB files inside the data repository, each fetched at the pinned commit
 */
@ConfigurationProperties(prefix = "facade.eob")
public record OralEobProperties(String dataBaseUrl, String dataRef, List<String> files) {

    public OralEobProperties {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
