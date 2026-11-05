package org.ohia.facade.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.ohia.facade.common.BadDataException;
import org.ohia.facade.config.FacadeProperties;
import org.ohia.facade.hapi.HapiClient;
import org.springframework.stereotype.Service;

/**
 * Loads the UC07 interaction-01 package into HAPI from the data repository at a pinned commit.
 *
 * <p>Order matters: everything is fetched and checked first (structure, and every ARex payload
 * against its declared SHA-256), and only then is anything written. A bad or tampered source
 * therefore changes nothing. The authored bundle is sent to HAPI byte for byte as authored.
 * Seeding twice is harmless: every entry is a PUT.
 */
@Service
public class SeedService {

    static final String ARex = "http://fhir.onyxhealth.io/us/arex/StructureDefinition/";
    private static final int MAX_BYTES = 10_000_000;
    private static final String ENTRY_PREFIX = "arex-entry-";

    private final FacadeProperties props;
    private final RemoteFetcher fetcher;
    private final HapiClient hapi;
    private final PackageStore store;
    private final ObjectMapper mapper;

    public SeedService(FacadeProperties props, RemoteFetcher fetcher, HapiClient hapi, PackageStore store,
            ObjectMapper mapper) {
        this.props = props;
        this.fetcher = fetcher;
        this.hapi = hapi;
        this.store = store;
        this.mapper = mapper;
    }

    public record SeedResult(String ref, String manifestId, int resources, Map<String, Integer> countsByType,
            int payloadsVerified) {
    }

    public SeedResult seed() {
        FacadeProperties.Seed s = props.seed();
        String base = strip(s.dataBaseUrl()) + "/" + s.dataRef() + "/" + strip(s.packagePath());

        byte[] bundleBytes = fetcher.fetch(base + "/interaction-01-bundle.json", MAX_BYTES);
        JsonNode bundle = parse(bundleBytes);
        if (!"Bundle".equals(bundle.path("resourceType").asText()) || !"transaction".equals(bundle.path("type").asText())
                || !bundle.path("entry").isArray() || bundle.path("entry").isEmpty()) {
            throw new BadDataException("The package file is not a non-empty transaction Bundle.");
        }

        List<PackageIndex.Res> resources = new ArrayList<>();
        Map<String, Integer> counts = new TreeMap<>();
        List<JsonNode> contentEntries = new ArrayList<>();
        String manifestId = null;
        for (JsonNode e : bundle.get("entry")) {
            JsonNode r = e.path("resource");
            String type = r.path("resourceType").asText();
            String id = r.path("id").asText();
            JsonNode req = e.path("request");
            if (type.isEmpty() || id.isEmpty() || !"PUT".equals(req.path("method").asText())
                    || !(type + "/" + id).equals(req.path("url").asText())) {
                throw new BadDataException("Every entry must be a PUT of its own Type/id; offending entry: " + type + "/" + id);
            }
            resources.add(new PackageIndex.Res(type, id, e.hasNonNull("fullUrl") ? e.get("fullUrl").asText() : null));
            counts.merge(type, 1, Integer::sum);
            if (type.equals("List") && hasProfile(r, "arex-manifest")) {
                if (manifestId != null) {
                    throw new BadDataException("More than one ARex manifest in the package.");
                }
                manifestId = id;
            }
            if (type.equals("DocumentReference") && hasProfile(r, "arex-content-entry")) {
                contentEntries.add(r);
            }
        }
        if (manifestId == null || contentEntries.isEmpty()) {
            throw new BadDataException("The package has no ARex manifest and content entries.");
        }

        Map<String, byte[]> payloads = new LinkedHashMap<>();
        List<PackageIndex.Payload> payloadIndex = new ArrayList<>();
        for (JsonNode r : contentEntries) {
            String id = r.path("id").asText();
            if (!id.startsWith(ENTRY_PREFIX)) {
                throw new BadDataException("Unexpected ARex entry id (expected " + ENTRY_PREFIX + "<name>): " + id);
            }
            JsonNode att = r.path("content").path(0).path("attachment");
            String declared = declaredSha256(att, id);
            byte[] data = fetcher.fetch(base + "/arex-payloads/radiograph-" + id.substring(ENTRY_PREFIX.length()) + ".png",
                    MAX_BYTES);
            String actual = sha256(data);
            if (!actual.equals(declared)) {
                throw new BadDataException("Digest mismatch for " + id + ": declared " + declared + ", computed " + actual);
            }
            if (att.path("size").asLong(-1) != data.length) {
                throw new BadDataException("Size mismatch for " + id + ": declared " + att.path("size").asLong(-1)
                        + ", actual " + data.length);
            }
            payloads.put(id, data);
            payloadIndex.add(new PackageIndex.Payload(id, att.path("contentType").asText("application/octet-stream"),
                    data.length, actual));
        }

        hapi.postTransaction(new String(bundleBytes, StandardCharsets.UTF_8));
        store.save(new PackageIndex(s.dataRef(), manifestId, resources, payloadIndex), payloads);
        return new SeedResult(s.dataRef(), manifestId, resources.size(), counts, payloadIndex.size());
    }

    private String declaredSha256(JsonNode attachment, String entryId) {
        for (JsonNode ext : attachment.path("extension")) {
            if ((ARex + "arex-digest").equals(ext.path("url").asText())) {
                String algorithm = null;
                String value = null;
                for (JsonNode part : ext.path("extension")) {
                    if ("algorithm".equals(part.path("url").asText())) {
                        algorithm = part.path("valueCode").asText();
                    } else if ("value".equals(part.path("url").asText())) {
                        value = part.path("valueString").asText();
                    }
                }
                if (!"sha-256".equals(algorithm) || value == null || value.isBlank()) {
                    throw new BadDataException("Entry " + entryId + " must declare a sha-256 digest.");
                }
                return value.toLowerCase();
            }
        }
        throw new BadDataException("Entry " + entryId + " has no ARex digest.");
    }

    private static boolean hasProfile(JsonNode resource, String name) {
        for (JsonNode p : resource.path("meta").path("profile")) {
            if (p.asText().equals(ARex + name)) {
                return true;
            }
        }
        return false;
    }

    private JsonNode parse(byte[] bytes) {
        try {
            return mapper.readTree(bytes);
        } catch (IOException e) {
            throw new BadDataException("The package file is not valid JSON: " + e.getMessage());
        }
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String strip(String s) {
        String t = s;
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        while (t.startsWith("/")) {
            t = t.substring(1);
        }
        return t;
    }
}
