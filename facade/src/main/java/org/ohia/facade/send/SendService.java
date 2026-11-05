package org.ohia.facade.send;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.ohia.facade.clients.ClientEntry;
import org.ohia.facade.clients.ClientRegistry;
import org.ohia.facade.common.BadDataException;
import org.ohia.facade.common.ForbiddenTargetException;
import org.ohia.facade.common.UseCase;
import org.ohia.facade.config.FacadeProperties;
import org.ohia.facade.seed.PackageReader;
import org.springframework.stereotype.Service;

/**
 * Sends a referral (UC07 or UC04a interaction 1; UC07 when none is named) to a registered tester, as one transaction Bundle POSTed
 * to their FHIR base URL.
 *
 * <p>Order matters. Everything that can refuse the request (unknown tester, target outside that
 * tester's allow-list, unsafe address) runs before HAPI is read or anything is written. The
 * manifest record is saved before delivery so the receiver can fetch images the moment the
 * referral arrives. The tester's own bearer token (if any) is used for the one call and is not
 * stored or logged.
 */
@Service
public class SendService {

    public record SendRequest(String clientId, String targetBaseUrl, String targetBearerToken, String useCase) {
        public SendRequest(String clientId, String targetBaseUrl, String targetBearerToken) {
            this(clientId, targetBaseUrl, targetBearerToken, null);
        }
    }

    private final ClientRegistry registry;
    private final SsrfGuard guard;
    private final PackageReader reader;
    private final ManifestStore manifests;
    private final OutboundClient outbound;
    private final DeliveryLog log;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String publicBaseUrl;
    private final int ttlDays;

    public SendService(ClientRegistry registry, SsrfGuard guard, PackageReader reader, ManifestStore manifests,
            OutboundClient outbound, DeliveryLog log, ObjectMapper mapper, Clock clock, FacadeProperties props) {
        this.registry = registry;
        this.guard = guard;
        this.reader = reader;
        this.manifests = manifests;
        this.outbound = outbound;
        this.log = log;
        this.mapper = mapper;
        this.clock = clock;
        String base = props.publicBaseUrl();
        this.publicBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.ttlDays = props.send().manifestTtlDays();
    }

    public SendRecord send(SendRequest req) {
        if (req == null || isBlank(req.clientId()) || isBlank(req.targetBaseUrl())) {
            throw new BadDataException("Both clientId and targetBaseUrl are required.");
        }
        ClientEntry client = registry.find(req.clientId())
                .orElseThrow(() -> new BadDataException("Unknown client '" + req.clientId() + "'. The organizer must register it first."));
        String target = stripTrailingSlashes(req.targetBaseUrl().trim());
        if (!allowedByPrefix(client, target)) {
            throw new ForbiddenTargetException("The target URL is not in this client's allowed target prefixes.");
        }
        URI uri = guard.validate(target);
        UseCase useCase = UseCase.parseOrDefault(req.useCase());

        ObjectNode packageBundle = reader.readBundle(useCase);

        String sendId = UUID.randomUUID().toString();
        String sendShort = sendId.substring(0, 8);
        Instant now = clock.instant();
        Instant expires = now.plus(ttlDays, ChronoUnit.DAYS);
        PerSendCopier.Copy copy = PerSendCopier.copy(packageBundle, sendShort, publicBaseUrl,
                client.organizationFor(useCase), expires, () -> UUID.randomUUID().toString());

        manifests.save(new ManifestRecord(copy.manifestId(), sendId, client.clientId(), client.organizationFor(useCase),
                now, expires, false, copy.referralId(), copy.manifest(), copy.entries()));

        String json;
        try {
            json = mapper.writeValueAsString(copy.bundle());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        OutboundClient.Delivery d = outbound.post(uri, json, req.targetBearerToken());

        String outcome = d.status() >= 200 && d.status() < 300 ? "delivered"
                : d.status() < 0 ? "unreachable" : "rejected-by-target";
        SendRecord record = new SendRecord(sendId, now, client.clientId(), target, copy.referralId(), copy.manifestId(),
                expires, outcome, d.status(), d.excerpt(), d.millis(), d.error(), useCase.id());
        log.append(record);
        return record;
    }

    static boolean allowedByPrefix(ClientEntry client, String target) {
        for (String prefix : client.allowedTargetPrefixes()) {
            String p = stripTrailingSlashes(prefix);
            if (target.equals(p) || target.startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String stripTrailingSlashes(String s) {
        String t = s;
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
