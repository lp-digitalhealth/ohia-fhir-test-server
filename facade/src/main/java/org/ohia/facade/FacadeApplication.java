package org.ohia.facade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * OHIA test server facade. Runs beside HAPI; Caddy routes only /ode/** and /arex/** here.
 * The /fhir path never goes through this service.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FacadeApplication {
    public static void main(String[] args) {
        SpringApplication.run(FacadeApplication.class, args);
    }
}
