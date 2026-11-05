package org.ohia.facade.send;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.ohia.facade.UcStubs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The deployed defaults: both test-only flags are left OFF. Even a target the registry
 * explicitly allows must be refused when it resolves to this machine or its network. The
 * allow-list names localhost on purpose, so the address check is what does the refusing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SendRefusalsTest {

    static final String TOKEN = "unit-test-token-123";
    static final Path dataDir = tempDir();
    static final Path clientsFile = dataDir.resolveSibling(dataDir.getFileName() + "-clients.json");
    static final ReceiverStub receiver = new ReceiverStub();

    static {
        try {
            Files.writeString(clientsFile, "{\"clients\":[{\"clientId\":\"tester-local\","
                    + "\"requestingOrganization\":\"Organization/org-restorative-dental-tx\","
                    + "\"allowedTargetPrefixes\":[\"https://localhost\",\"https://localhost:9\",\"https://127.0.0.1\","
                    + "\"https://127.0.0.1:" + receiver.baseUrl().split(":")[2].split("/")[0] + "\",\"https://hapi\",\"https://hapi:8080\"],"
                    + "\"jwks\":{\"keys\":[]}}]}", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("facade.api-token", () -> TOKEN);
        r.add("facade.data-dir", dataDir::toString);
        r.add("facade.clients-file", clientsFile::toString);
        r.add("facade.hapi-base-url", UcStubs::hapiBaseUrl);
        r.add("facade.seed.data-base-url", UcStubs::dataBaseUrl);
        r.add("facade.seed.data-ref", () -> UcStubs.REF);
        r.add("facade.seed.package-path", () -> UcStubs.PKG);
        // facade.send.* deliberately not set: the production defaults apply
    }

    @Autowired
    MockMvc mvc;

    private org.springframework.test.web.servlet.ResultActions send(String target) throws Exception {
        UcStubs.reset();
        return mvc.perform(post("/ode/send").header("Authorization", "Bearer " + TOKEN).contentType("application/json")
                .content("{\"clientId\":\"tester-local\",\"targetBaseUrl\":\"" + target + "\"}"));
    }

    @Test
    void loopbackNamesAndAddressesAreRefusedEvenWhenAllowListed() throws Exception {
        for (String target : new String[] {"https://localhost/fhir", "https://localhost:9/fhir", "https://127.0.0.1/fhir",
                "https://127.0.0.1:" + receiver.baseUrl().split(":")[2].split("/")[0] + "/fhir"}) {
            send(target).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("non-public")));
        }
        assertEquals(0, receiver.received.size());
        assertEquals(0, UcStubs.posts.get());
    }

    @Test
    void theComposeInternalNameOfHapiIsNotATarget() throws Exception {
        // 'hapi' does not resolve here, and on the compose network it resolves to a private address;
        // both outcomes must be a refusal, never a connection.
        send("https://hapi:8080/fhir").andExpect(status().isForbidden());
    }

    @Test
    void plainHttpIsRefusedByDefault() throws Exception {
        // The registry itself also refuses http prefixes by default, so use an allow-listed https prefix
        // and ask for http on the same host: the allow-list rejects it first, which is also a refusal.
        send("http://localhost/fhir").andExpect(status().isForbidden());
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("facade-test-refusals");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
