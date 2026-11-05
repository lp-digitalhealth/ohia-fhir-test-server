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
 * The FHIR base's CapabilityStatement, for a TEST server: it states the capability the server is going for, and says in
 * its own text what is implemented today. It is a plain file (capability/capability-statement.json) with {{BASE}}
 * replaced by this deployment's public address, so it is edited as data, not code. Caddy routes GET /fhir/metadata here
 * (and falls back to HAPI's own statement if this service is down). The statement never claims a way to authenticate
 * that does not exist: the shared token and the target are described in words only.
 */
@RestController
public class CapabilityController {

    private static final MediaType FHIR_JSON = MediaType.parseMediaType("application/fhir+json;charset=UTF-8");

    private final String statement;

    public CapabilityController(FacadeProperties props, ObjectMapper mapper) {
        String base = props.publicBaseUrl().replaceAll("/+$", "");
        try (InputStream in = CapabilityController.class.getResourceAsStream("/capability/capability-statement.json")) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("{{BASE}}", base);
            mapper.readTree(text); // a broken file must stop the start-up, not be served
            this.statement = text;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping("/fhir/metadata")
    public ResponseEntity<String> metadata() {
        return ResponseEntity.ok().contentType(FHIR_JSON).cacheControl(CacheControl.noCache()).body(statement);
    }
}
