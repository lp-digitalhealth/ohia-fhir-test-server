package org.ohia.facade.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import org.ohia.facade.common.NotSeededException;
import org.ohia.facade.common.UseCase;
import org.ohia.facade.common.UpstreamException;
import org.ohia.facade.hapi.HapiClient;
import org.springframework.stereotype.Service;

/**
 * Reads the seeded UC07 package back out of HAPI (the facade's source of truth) as a
 * transaction Bundle. The list of resources comes from the package index; the content comes
 * from HAPI. If HAPI no longer has a resource, this fails loudly rather than sending a
 * partial referral.
 */
@Service
public class PackageReader {

    private final PackageStore store;
    private final HapiClient hapi;
    private final ObjectMapper mapper;

    public PackageReader(PackageStore store, HapiClient hapi, ObjectMapper mapper) {
        this.store = store;
        this.hapi = hapi;
        this.mapper = mapper;
    }

    public ObjectNode readBundle() {
        return readBundle(UseCase.UC07);
    }

    public ObjectNode readBundle(UseCase useCase) {
        PackageIndex index = store.load(useCase).orElseThrow(() -> new NotSeededException(useCase));
        ObjectNode bundle = mapper.createObjectNode();
        bundle.put("resourceType", "Bundle");
        bundle.put("type", "transaction");
        ArrayNode entries = bundle.putArray("entry");
        for (PackageIndex.Res r : index.resources()) {
            String json = hapi.read(r.type(), r.id()).orElseThrow(() -> new UpstreamException(404,
                    "HAPI does not have " + r.type() + "/" + r.id() + ". Re-seed with POST /ode/admin/seed?useCase=" + useCase.id() + "."));
            ObjectNode resource = parseObject(json);
            stripServerMeta(resource);
            ObjectNode entry = entries.addObject();
            if (r.fullUrl() != null) {
                entry.put("fullUrl", r.fullUrl());
            }
            entry.set("resource", resource);
            ObjectNode request = entry.putObject("request");
            request.put("method", "PUT");
            request.put("url", r.type() + "/" + r.id());
        }
        return bundle;
    }

    /** HAPI stamps versionId and lastUpdated; they are the server's, not part of the authored data. */
    static void stripServerMeta(ObjectNode resource) {
        JsonNode meta = resource.get("meta");
        if (meta instanceof ObjectNode m) {
            m.remove("versionId");
            m.remove("lastUpdated");
            if (m.isEmpty()) {
                resource.remove("meta");
            }
        }
    }

    private ObjectNode parseObject(String json) {
        try {
            JsonNode n = mapper.readTree(json);
            if (n instanceof ObjectNode o) {
                return o;
            }
        } catch (IOException e) {
            throw new UpstreamException("HAPI returned invalid JSON: " + e.getMessage(), e);
        }
        throw new UpstreamException(502, "HAPI returned a non-object resource.");
    }
}
