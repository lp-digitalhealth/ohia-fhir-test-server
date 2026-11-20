package org.ohia.facade.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets browser-based patient apps call the patient-app FHIR base: cross-origin reads with the Authorization header are
 * permitted on {@code /patient-access/fhir/**}. The token is a bearer header, not a cookie, and the data is synthetic.
 */
@Configuration
public class PatientAccessCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/patient-access/fhir/**").allowedOriginPatterns("*").allowedMethods("GET", "HEAD", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "Accept").maxAge(600);
    }
}
