package org.ohia.facade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.ohia.facade.arexclient.ArexClient;

/** The real UC04a interaction-1 payload (copied into src/test/resources/uc04a-i1, see the README there). */
public final class Uc04aFixture {

    public static final String BUNDLE = "/uc04a-i1/interaction-01-bundle.json";
    public static final String MANIFEST_ID = "arex-manifest-intraoral-photos";
    public static final String ARex = "http://fhir.onyxhealth.io/us/arex/StructureDefinition/";

    private Uc04aFixture() {
    }

    public static byte[] resource(String name) {
        try (var in = Uc04aFixture.class.getResourceAsStream("/uc04a-i1/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The authored bundle as text (what a tester would POST, with the example.org URLs). */
    public static String bundleText() {
        return new String(resource("interaction-01-bundle.json"), java.nio.charset.StandardCharsets.UTF_8);
    }

    public static ObjectNode bundle() {
        try {
            return (ObjectNode) UcStubs.M.readTree(bundleText());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ObjectNode manifest(ObjectNode bundle) {
        for (JsonNode e : bundle.get("entry")) {
            JsonNode r = e.get("resource");
            if (r.get("resourceType").asText().equals("List") && hasProfile(r, "arex-manifest")) {
                return (ObjectNode) r;
            }
        }
        throw new IllegalStateException("no manifest");
    }

    public static List<ObjectNode> contentEntries(ObjectNode bundle) {
        List<ObjectNode> out = new ArrayList<>();
        for (JsonNode e : bundle.get("entry")) {
            JsonNode r = e.get("resource");
            if (r.get("resourceType").asText().equals("DocumentReference") && hasProfile(r, "arex-content-entry")) {
                out.add((ObjectNode) r);
            }
        }
        return out;
    }

    /** Points every content URL of the payload at {@code senderBase} (the stand-in sender) and returns the refs. */
    public static List<ArexClient.ContentRef> refs(ObjectNode bundle, String senderBase) {
        List<ArexClient.ContentRef> refs = new ArrayList<>();
        for (ObjectNode entry : contentEntries(bundle)) {
            ObjectNode att = (ObjectNode) entry.get("content").get(0).get("attachment");
            String url = senderBase + "/arex/Binary/" + entry.get("id").asText();
            att.put("url", url);
            refs.add(new ArexClient.ContentRef(entry.get("id").asText(), url, att.get("contentType").asText(),
                    att.get("size").asLong(), digest(att)));
        }
        return refs;
    }

    /** The two photos by entry id. */
    public static Map<String, byte[]> photos() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("arex-entry-photo-1", resource("intraoral-photo-1.jpg"));
        m.put("arex-entry-photo-2", resource("intraoral-photo-2.jpg"));
        return m;
    }

    public static String digest(JsonNode attachment) {
        for (JsonNode ext : attachment.path("extension")) {
            if (ext.path("url").asText().endsWith("arex-digest")) {
                for (JsonNode part : ext.path("extension")) {
                    if ("value".equals(part.path("url").asText())) {
                        return part.path("valueString").asText();
                    }
                }
            }
        }
        throw new IllegalStateException("no digest");
    }

    private static boolean hasProfile(JsonNode resource, String name) {
        for (JsonNode p : resource.path("meta").path("profile")) {
            if (p.asText().equals(ARex + name)) {
                return true;
            }
        }
        return false;
    }
}
