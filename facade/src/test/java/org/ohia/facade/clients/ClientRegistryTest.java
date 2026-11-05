package org.ohia.facade.clients;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ohia.facade.common.BadDataException;
import org.ohia.facade.config.FacadeProperties;

class ClientRegistryTest {

    @TempDir
    Path dir;
    Path file;

    @BeforeEach
    void setUp() {
        file = dir.resolve("clients.json");
    }

    private ClientRegistry registry(boolean allowHttp) {
        return new ClientRegistry(new FacadeProperties("h", "p", "t", "/d", file.toString(),
                new FacadeProperties.Seed("u", "r", "p"), new FacadeProperties.Send(allowHttp, false, 30)),
                new ObjectMapper());
    }

    private void write(String json, long epochSecond) throws IOException {
        Files.writeString(file, json, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.fromMillis(epochSecond * 1000));
    }

    private static String client(String id, String org, String prefix) {
        return "{\"clients\":[{\"clientId\":\"" + id + "\",\"requestingOrganization\":\"" + org
                + "\",\"allowedTargetPrefixes\":[\"" + prefix + "\"],\"jwks\":{\"keys\":[]}}]}";
    }

    @Test
    void aMissingFileMeansNobodyIsRegistered() {
        assertTrue(registry(false).find("tester-a").isEmpty());
    }

    @Test
    void findsARegisteredClient() throws IOException {
        write(client("tester-a", "Organization/org-x", "https://a.example.org/fhir"), 1_000);
        ClientEntry c = registry(false).find("tester-a").orElseThrow();
        assertEquals("Organization/org-x", c.requestingOrganization());
        assertEquals("https://a.example.org/fhir", c.allowedTargetPrefixes().get(0));
        assertTrue(registry(false).find("someone-else").isEmpty());
    }

    @Test
    void picksUpAnEditedFileWithoutARestart() throws IOException {
        ClientRegistry r = registry(false);
        write(client("tester-a", "Organization/org-x", "https://a.example.org/fhir"), 1_000);
        assertTrue(r.find("tester-a").isPresent());
        assertTrue(r.find("tester-b").isEmpty());
        write(client("tester-b", "Organization/org-y", "https://b.example.org/fhir"), 2_000);
        assertTrue(r.find("tester-b").isPresent());
        assertTrue(r.find("tester-a").isEmpty(), "the replaced file is the whole truth");
    }

    @Test
    void refusesAMalformedFileAsAWholeInsteadOfHalfApplyingIt() throws IOException {
        write(client("tester-a", "not-an-org-reference", "https://a.example.org/fhir"), 1_000);
        assertThrows(BadDataException.class, () -> registry(false).find("tester-a"));
        write(client("bad id!", "Organization/org-x", "https://a.example.org/fhir"), 2_000);
        assertThrows(BadDataException.class, () -> registry(false).find("x"));
        write("{\"clients\":[" + client("a", "Organization/o", "https://a.org/f").substring(12, client("a", "Organization/o", "https://a.org/f").length() - 2)
                + "," + client("a", "Organization/o", "https://a.org/f").substring(12, client("a", "Organization/o", "https://a.org/f").length() - 2) + "]}", 3_000);
        assertThrows(BadDataException.class, () -> registry(false).find("a"), "duplicate ids must be refused");
    }

    @Test
    void httpPrefixesAreRefusedUnlessTheTestOnlyFlagIsOn() throws IOException {
        write(client("tester-a", "Organization/org-x", "http://a.example.org/fhir"), 1_000);
        assertThrows(BadDataException.class, () -> registry(false).find("tester-a"));
        assertTrue(registry(true).find("tester-a").isPresent());
    }
}
