package org.ohia.facade.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.ohia.facade.common.PayloadTooLargeException;
import org.ohia.facade.receive.ReceiptStore;
import org.ohia.facade.receive.Receipt;
import org.ohia.facade.receive.ReceiveService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receive a UC04a payload and look at the results. All of /ode/** needs the shared token
 * (Caddy, and OdeTokenFilter again).
 */
@RestController
public class ReceiveController {

    private final ReceiveService service;
    private final ReceiptStore store;

    public ReceiveController(ReceiveService service, ReceiptStore store) {
        this.service = service;
        this.store = store;
    }

    /**
     * The body is read here, with a hard cap, before anything is parsed. The answer is 200 with the
     * receipt even when the pull failed or stopped: that is a result to report, not an error.
     */
    @PostMapping(value = "/ode/receive/uc04a", consumes = {"application/json", "application/fhir+json"})
    public Receipt receive(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(ReceiveService.MAX_BODY_BYTES + 1);
        if (body.length > ReceiveService.MAX_BODY_BYTES) {
            throw new PayloadTooLargeException("The payload is larger than " + ReceiveService.MAX_BODY_BYTES + " bytes.");
        }
        return service.receive(new String(body, StandardCharsets.UTF_8));
    }

    @GetMapping("/ode/receipts")
    public ResponseEntity<List<Receipt>> recent(@RequestParam(defaultValue = "20") int limit) {
        return noStore(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)).body(store.recent(Math.max(1, Math.min(limit, 100))));
    }

    @GetMapping("/ode/receipts/{id}")
    public ResponseEntity<Receipt> one(@PathVariable String id) {
        return store.load(id).map(r -> noStore(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)).body(r))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * A pulled object. Bytes are shown inline ONLY if they really are a JPEG or PNG (decided from the
     * bytes, never from the declared type). Anything else is a forced download, so a sender cannot plant
     * a file that would run as a page on this origin.
     */
    @GetMapping("/ode/receipts/{id}/images/{fileKey}")
    public ResponseEntity<byte[]> image(@PathVariable String id, @PathVariable String fileKey) {
        Receipt receipt = store.load(id).orElse(null);
        byte[] bytes = receipt == null ? null : store.image(id, fileKey).orElse(null);
        if (bytes == null) {
            return ResponseEntity.notFound().build();
        }
        boolean viewable = receipt.objects().stream().anyMatch(o -> fileKey.equals(o.fileKey()) && o.viewable());
        String sniffed = org.ohia.facade.receive.ImageSniffer.sniff(bytes);
        ResponseEntity.BodyBuilder b = noStore(ResponseEntity.ok()).header("X-Content-Type-Options", "nosniff");
        if (viewable && sniffed != null) {
            return b.contentType(MediaType.parseMediaType(sniffed)).header(HttpHeaders.CONTENT_DISPOSITION, "inline").body(bytes);
        }
        return b.contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileKey + ".bin\"").body(bytes);
    }

    private static ResponseEntity.BodyBuilder noStore(ResponseEntity.BodyBuilder b) {
        return b.header(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
