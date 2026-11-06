package org.ohia.facade.arex;

import java.time.Clock;
import java.util.Optional;
import org.ohia.facade.clients.ClientEntry;
import org.ohia.facade.clients.ClientRegistry;
import org.ohia.facade.send.ManifestRecord;
import org.ohia.facade.send.ManifestStore;
import org.springframework.stereotype.Component;

/**
 * The entitlement check, used both when a token is requested and on every retrieval.
 * A caller is entitled to a manifest only if ALL of these hold right now:
 * the manifest exists; it was published for this very client; an organization the client is registered as
 * is the manifest's requesting organization (authenticating is not authorizing);
 * the manifest has not expired; and it has not been retracted. Expiry is checked here, at use,
 * so an unexpired token cannot outlive its manifest.
 */
@Component
public class ArexAccess {

    private final ManifestStore manifests;
    private final ClientRegistry registry;
    private final Clock clock;

    public ArexAccess(ManifestStore manifests, ClientRegistry registry, Clock clock) {
        this.manifests = manifests;
        this.registry = registry;
        this.clock = clock;
    }

    /** The manifest if the client may use it, otherwise empty. The caller must not say which check failed. */
    public Optional<ManifestRecord> entitled(String clientId, String manifestId) {
        Optional<ManifestRecord> found = manifests.load(manifestId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ManifestRecord m = found.get();
        if (!m.clientId().equals(clientId) || m.retracted() || !clock.instant().isBefore(m.expiresAt())) {
            return Optional.empty();
        }
        Optional<ClientEntry> client = registry.find(clientId);
        if (client.isEmpty() || !client.get().plays(m.requestingOrganization())) {
            return Optional.empty();
        }
        return Optional.of(m);
    }
}
