package org.ohia.facade.send;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.ohia.facade.common.ForbiddenTargetException;
import org.ohia.facade.config.FacadeProperties;

class SsrfGuardTest {

    private static SsrfGuard guard(boolean allowHttp, boolean allowPrivate) {
        return new SsrfGuard(new FacadeProperties("http://hapi:8080/fhir", "https://x.test", "t", "/data", "/c.json",
                new FacadeProperties.Seed("u", "r", "p"), new FacadeProperties.Send(allowHttp, allowPrivate, 30),
                new FacadeProperties.Client("ohia-test-server", "/data/keys/server-key.json", "Organization/org-barton-springs-dental", "arex-manifest", 20, 10_000_000L, 50_000_000L, 5, 60)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://127.0.0.1/fhir", "https://127.1.2.3/fhir", "https://10.0.0.5/fhir", "https://172.16.0.9/fhir",
            "https://172.17.0.2:8080/fhir", "https://192.168.1.10/fhir", "https://169.254.169.254/latest/meta-data",
            "https://100.64.0.1/fhir", "https://100.127.255.254/fhir", "https://0.0.0.0/fhir", "https://0.1.2.3/fhir",
            "https://224.0.0.1/fhir", "https://255.255.255.255/fhir", "https://[::1]/fhir", "https://[fd00::1]/fhir",
            "https://[fe80::1]/fhir", "https://localhost/fhir", "https://localhost:8080/fhir"})
    void refusesNonPublicAddresses(String url) {
        assertThrows(ForbiddenTargetException.class, () -> guard(false, false).validate(url), url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://1.1.1.1/fhir", "ftp://1.1.1.1/fhir", "file:///etc/passwd", "gopher://1.1.1.1/",
            "https://user:pass@1.1.1.1/fhir", "https:///fhir", "not a url", "https://"})
    void refusesWrongSchemesCredentialsAndMalformedUrls(String url) {
        assertThrows(ForbiddenTargetException.class, () -> guard(false, false).validate(url), url);
    }

    @Test
    void acceptsAPublicAddressOverHttps() {
        assertDoesNotThrow(() -> guard(false, false).validate("https://1.1.1.1/fhir"));
        assertDoesNotThrow(() -> guard(false, false).validate("https://8.8.8.8:8443/fhir"));
        assertDoesNotThrow(() -> guard(false, false).validate("https://[2606:4700:4700::1111]/fhir"));
    }

    /** The test-only switches work, and they are the only way a private or http target gets through. */
    @Test
    void testOnlyFlagsOpenTheDoorAndDefaultsKeepItShut() {
        assertThrows(ForbiddenTargetException.class, () -> guard(false, false).validate("http://127.0.0.1/fhir"));
        assertThrows(ForbiddenTargetException.class, () -> guard(true, false).validate("http://127.0.0.1/fhir"));
        assertThrows(ForbiddenTargetException.class, () -> guard(false, true).validate("http://127.0.0.1/fhir"));
        assertDoesNotThrow(() -> guard(true, true).validate("http://127.0.0.1/fhir"));
    }
}
