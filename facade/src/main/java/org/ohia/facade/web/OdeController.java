package org.ohia.facade.web;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.ohia.facade.common.UseCase;
import org.ohia.facade.seed.PackageIndex;
import org.ohia.facade.seed.PackageReader;
import org.ohia.facade.seed.PackageStore;
import org.ohia.facade.seed.SeedService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Organizer endpoints. All of /ode/** requires the shared S1 token (see OdeTokenFilter). */
@RestController
@RequestMapping("/ode")
public class OdeController {

    private final SeedService seedService;
    private final PackageReader reader;
    private final PackageStore store;

    public OdeController(SeedService seedService, PackageReader reader, PackageStore store) {
        this.seedService = seedService;
        this.reader = reader;
        this.store = store;
    }

    /** Loads (or reloads) a use case's pinned interaction-1 package into HAPI (UC07 unless useCase=uc04a). Idempotent. */
    @PostMapping("/admin/seed")
    public SeedService.SeedResult seed(@RequestParam(required = false) String useCase) {
        return seedService.seed(UseCase.parseOrDefault(useCase));
    }

    /** What has been seeded, without the resource bodies. */
    @GetMapping("/package")
    public Map<String, Object> packageSummary(@RequestParam(required = false) String useCase) {
        Map<String, Object> m = new LinkedHashMap<>();
        PackageIndex idx = store.load(UseCase.parseOrDefault(useCase)).orElse(null);
        m.put("seeded", idx != null);
        if (idx != null) {
            m.put("ref", idx.ref());
            m.put("manifestId", idx.manifestId());
            m.put("resources", idx.resources().size());
            m.put("payloads", idx.payloads());
        }
        return m;
    }

    /** The package as read back from HAPI, as a transaction Bundle (what the sender starts from). */
    @GetMapping("/package/bundle")
    public ObjectNode packageBundle(@RequestParam(required = false) String useCase) {
        return reader.readBundle(UseCase.parseOrDefault(useCase));
    }
}
