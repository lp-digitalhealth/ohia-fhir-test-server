package org.ohia.facade.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.ohia.facade.config.FacadeProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The CapabilityStatement of the patient-app FHIR base, open (no token) like the one at /fhir/metadata. It is a plain
 * file (capability/patient-access-capability-statement.json) with {{BASE}} replaced by this deployment's public address.
 * It says what is implemented and what is not, and never claims a way to authenticate that does not exist.
 */
@RestController
public class PatientAccessCapabilityController {

    private static final MediaType FHIR_JSON = MediaType.parseMediaType("application/fhir+json;charset=UTF-8");

    private final String statement;

    public PatientAccessCapabilityController(FacadeProperties props, ObjectMapper mapper) {
        String base = props.publicBaseUrl().replaceAll("/+$", "");
        try (InputStream in = PatientAccessCapabilityController.class.getResourceAsStream("/capability/patient-access-capability-statement.json")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("{{BASE}}", base);
            mapper.readTree(text); // a broken file must stop the start-up, not be served
            this.statement = text;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping("/patient-access/fhir/metadata")
    public ResponseEntity<String> metadata() {
        return ResponseEntity.ok().contentType(FHIR_JSON).cacheControl(CacheControl.noCache()).body(statement);
    }
}
