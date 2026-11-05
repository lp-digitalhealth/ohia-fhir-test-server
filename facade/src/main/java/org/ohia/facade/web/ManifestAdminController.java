package org.ohia.facade.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.ohia.facade.send.ManifestRecord;
import org.ohia.facade.send.ManifestStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Organizer view of sent manifests, and retraction (ARex: set status to retracted). S1 token required. */
@RestController
public class ManifestAdminController {

    private final ManifestStore store;

    public ManifestAdminController(ManifestStore store) {
        this.store = store;
    }

    @GetMapping("/ode/manifests/{manifestId}")
    public ResponseEntity<Map<String, Object>> show(@PathVariable String manifestId) {
        return store.load(manifestId).map(m -> ResponseEntity.ok(summary(m))).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Withdraws a manifest. From this moment no token can retrieve it or its content, and no new
     * token can be issued for it, whatever time is left on the manifest or on any outstanding token.
     */
    @PostMapping("/ode/manifests/{manifestId}/retract")
    public ResponseEntity<Map<String, Object>> retract(@PathVariable String manifestId) {
        ManifestRecord m = store.load(manifestId).orElse(null);
        if (m == null) {
            return ResponseEntity.notFound().build();
        }
        JsonNode copy = m.manifest().deepCopy();
        ((ObjectNode) copy).put("status", "retracted");
        ManifestRecord retracted = new ManifestRecord(m.manifestId(), m.sendId(), m.clientId(), m.requestingOrganization(),
                m.createdAt(), m.expiresAt(), true, m.referralId(), copy, m.entries());
        store.save(retracted);
        return ResponseEntity.ok(summary(retracted));
    }

    private static Map<String, Object> summary(ManifestRecord m) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("manifestId", m.manifestId());
        s.put("sendId", m.sendId());
        s.put("clientId", m.clientId());
        s.put("requestingOrganization", m.requestingOrganization());
        s.put("referralId", m.referralId());
        s.put("createdAt", m.createdAt());
        s.put("expiresAt", m.expiresAt());
        s.put("retracted", m.retracted());
        s.put("contentObjects", m.entries().size());
        s.put("entries", m.entries().stream().map(e -> {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("entryId", e.originalEntryId());
            o.put("contentType", e.contentType());
            o.put("size", e.size());
            o.put("sha256", e.sha256());
            return o;
        }).toList());
        return s;
    }
}
