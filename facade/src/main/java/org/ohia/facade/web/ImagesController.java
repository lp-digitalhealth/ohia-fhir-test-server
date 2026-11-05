package org.ohia.facade.web;

import java.util.Optional;
import org.ohia.facade.common.UseCase;
import org.ohia.facade.send.DeliveryLog;
import org.ohia.facade.send.ManifestStore;
import org.ohia.facade.send.PerSendCopier;
import org.ohia.facade.send.SendRecord;
import org.ohia.facade.seed.PackageIndex;
import org.ohia.facade.seed.PackageStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The images WE send, for the page: the ones in a seeded package (what the next send will carry) and the ones a
 * particular send carried. These are our own verified payloads, but they go through the same inline-only-if-a-real-image
 * rule as received ones. All of /ode/** needs the shared token.
 */
@RestController
public class ImagesController {

    private final PackageStore packages;
    private final DeliveryLog log;
    private final ManifestStore manifests;

    public ImagesController(PackageStore packages, DeliveryLog log, ManifestStore manifests) {
        this.packages = packages;
        this.log = log;
        this.manifests = manifests;
    }

    /** An image of the seeded package of a use case. The entry must be one the package's index lists. */
    @GetMapping("/ode/package/images/{useCase}/{entryId}")
    public ResponseEntity<byte[]> packageImage(@PathVariable String useCase, @PathVariable String entryId) {
        PackageIndex index = packages.load(UseCase.parse(useCase)).orElse(null);
        if (index == null || index.payloads().stream().noneMatch(p -> p.entryId().equals(entryId))) {
            return ResponseEntity.notFound().build();
        }
        return image(entryId);
    }

    /** An image that one send carried: the send's manifest must list the entry. */
    @GetMapping("/ode/sends/{sendId}/images/{entryId}")
    public ResponseEntity<byte[]> sentImage(@PathVariable String sendId, @PathVariable String entryId) {
        Optional<SendRecord> send = log.find(sendId);
        boolean carried = send.flatMap(s -> manifests.load(s.manifestId()))
                .map(m -> m.entries().stream().map(PerSendCopier.Entry::originalEntryId).anyMatch(entryId::equals)).orElse(false);
        return carried ? image(entryId) : ResponseEntity.notFound().build();
    }

    private ResponseEntity<byte[]> image(String entryId) {
        return packages.payload(entryId).map(bytes -> ImageResponse.of(bytes, true, entryId)).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
