package org.ohia.facade.send;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ohia.facade.UcStubs;

/** Works on the real I1 bundle (the authored bundle has the same shape the read-back produces). */
class PerSendCopierTest {

    static final String ORIGINAL_REFERRAL_ID = "REF-2026-UC07-001";
    static final Instant EXPIRES = Instant.parse("2026-12-05T10:00:00Z");

    ObjectNode original;
    String originalJson;
    PerSendCopier.Copy copy;

    @BeforeEach
    void setUp() throws Exception {
        originalJson = UcStubs.fixtureText("interaction-01-bundle.json");
        original = (ObjectNode) UcStubs.M.readTree(originalJson);
        AtomicInteger n = new AtomicInteger();
        copy = PerSendCopier.copy(original, "ab12cd34", "https://facade.test", "Organization/org-pediatric-dental-tx", EXPIRES,
                () -> "opaque-" + n.incrementAndGet());
    }

    @Test
    void doesNotChangeTheInput() throws Exception {
        assertEquals(UcStubs.M.readTree(originalJson), original);
    }

    @Test
    void renamesExactlyTheInteractionResourcesAndKeepsTheStableOnes() {
        int renamed = 0;
        int kept = 0;
        for (int i = 0; i < original.get("entry").size(); i++) {
            JsonNode o = original.get("entry").get(i).get("resource");
            JsonNode c = copy.bundle().get("entry").get(i).get("resource");
            String type = o.get("resourceType").asText();
            if (PerSendCopier.INTERACTION_TYPES.contains(type)) {
                assertEquals(o.get("id").asText() + "-ab12cd34", c.get("id").asText());
                renamed++;
            } else {
                assertEquals(o.get("id").asText(), c.get("id").asText(), type + " must keep its authored id");
                kept++;
            }
        }
        assertEquals(22, renamed);
        assertEquals(15, kept);
    }

    @Test
    void everyEntryUrlMatchesItsResourceAndEveryReferenceStillResolves() {
        Set<String> present = new HashSet<>();
        for (JsonNode e : copy.bundle().get("entry")) {
            JsonNode r = e.get("resource");
            String key = r.get("resourceType").asText() + "/" + r.get("id").asText();
            assertEquals(key, e.get("request").get("url").asText());
            assertEquals("PUT", e.get("request").get("method").asText());
            present.add(key);
        }
        int checked = collectReferences(copy.bundle(), present);
        assertTrue(checked > 40, "expected many references, found " + checked);
    }

    private int collectReferences(JsonNode node, Set<String> present) {
        int count = 0;
        if (node.isObject()) {
            var it = node.fields();
            while (it.hasNext()) {
                var f = it.next();
                if (f.getKey().equals("reference") && f.getValue().isTextual()) {
                    assertTrue(present.contains(f.getValue().asText()), "dangling reference " + f.getValue().asText());
                    count++;
                } else {
                    count += collectReferences(f.getValue(), present);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode c : node) {
                count += collectReferences(c, present);
            }
        }
        return count;
    }

    @Test
    void theReferralIdIsReplacedEverywhereAndOnlyWithTheSuffixedValue() throws Exception {
        String sent = UcStubs.M.writeValueAsString(copy.bundle());
        assertEquals(ORIGINAL_REFERRAL_ID + "-ab12cd34", copy.referralId());
        assertFalse(sent.contains("\"" + ORIGINAL_REFERRAL_ID + "\""), "no bare original id may remain");
        assertFalse(sent.replace(ORIGINAL_REFERRAL_ID + "-ab12cd34", "").contains(ORIGINAL_REFERRAL_ID),
                "every mention must carry the suffix");
        assertTrue(sent.contains(ORIGINAL_REFERRAL_ID + "-ab12cd34"));
    }

    @Test
    void arexManifestGetsAFreshExpiryTheRegisteredOrganizationAndANewIdentifier() throws Exception {
        assertEquals("arex-manifest-radiographs-ab12cd34", copy.manifestId());
        String sent = UcStubs.M.writeValueAsString(copy.manifest());
        assertTrue(sent.contains("\"valueInstant\":\"2026-12-05T10:00:00Z\""), sent);
        assertFalse(sent.contains("2026-07-22"), "the authored (already past) expiry must be gone");
        // the requesting organization in the transaction context is the registered one, not the authored one
        assertEquals("Organization/org-pediatric-dental-tx", manifestRequestingOrg(copy));
        assertFalse(sent.contains("Organization/org-restorative-dental-tx"),
                "the authored requesting organization must be gone from the manifest");
        assertTrue(sent.contains("MAN-2026-0708-0001-ab12cd34"));
        assertTrue(sent.contains("arex-requires-access-token"));
    }

    @Test
    void contentUrlsPointAtThisServerWithOpaqueIdsAndDigestsAreUntouched() throws Exception {
        String sent = UcStubs.M.writeValueAsString(copy.bundle());
        assertFalse(sent.contains("pediatricdental.example.org/arex/Binary"), "authored example.org URLs must be gone");
        assertTrue(sent.contains("https://facade.test/arex/Binary/opaque-1"));
        assertTrue(sent.contains("https://facade.test/arex/Binary/opaque-2"));
        assertEquals(2, copy.entries().size());
        for (PerSendCopier.Entry e : copy.entries()) {
            assertTrue(e.originalEntryId().startsWith("arex-entry-"), e.originalEntryId());
            assertEquals("image/png", e.contentType());
            assertTrue(e.size() == 137 || e.size() == 140);
            assertTrue(e.sha256().matches("[0-9a-f]{64}"));
            assertTrue(sent.contains(e.sha256()), "the declared digest must still be in the sent entry");
            assertFalse(e.binaryId().contains("REF"), "opaque ids must not carry the referral id");
        }
    }

    @Test
    void twoCopiesAreSeparateReferrals() {
        PerSendCopier.Copy other = PerSendCopier.copy(original, "99aa88bb", "https://facade.test",
                "Organization/org-pediatric-dental-tx", EXPIRES, () -> "z");
        assertNotEquals(copy.referralId(), other.referralId());
        assertNotEquals(copy.manifestId(), other.manifestId());
    }

    private static String manifestRequestingOrg(PerSendCopier.Copy c) {
        for (JsonNode ext : c.manifest().path("extension")) {
            for (JsonNode part : ext.path("extension")) {
                if ("requestingOrganization".equals(part.path("url").asText())) {
                    return part.path("valueReference").path("reference").asText();
                }
            }
        }
        return "";
    }

    /** Control: an organization that is not in the package must be refused, not sent as a dangling reference. */
    @Test
    void refusesARequestingOrganizationThatIsNotInThePackage() {
        org.ohia.facade.common.BadDataException e = org.junit.jupiter.api.Assertions.assertThrows(
                org.ohia.facade.common.BadDataException.class, () -> PerSendCopier.copy(original, "ab12cd34",
                        "https://facade.test", "Organization/org-not-in-package", EXPIRES, () -> "z"));
        assertTrue(e.getMessage().contains("org-not-in-package"));
    }
}
