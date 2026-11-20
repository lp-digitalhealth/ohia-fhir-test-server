package org.ohia.facade.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.ohia.facade.common.BadDataException;
import org.ohia.facade.common.PayloadTooLargeException;
import org.ohia.facade.common.UpstreamException;
import org.ohia.facade.common.UseCase;
import org.ohia.facade.config.FacadeProperties;
import org.ohia.facade.receive.ReceiptStore;
import org.ohia.facade.receive.Receipt;
import org.ohia.facade.receive.ReceivePullExecutor;
import org.ohia.facade.receive.ReceiveService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receive a UC04a or UC07 referral and look at the results. All of /ode/** needs the shared token
 * (Caddy, and OdeTokenFilter again).
 */
@RestController
public class ReceiveController {

    private static final MediaType FHIR_JSON = MediaType.parseMediaType("application/fhir+json");
    private final ReceiveService service;
    private final ReceiptStore store;
    private final ReceivePullExecutor executor;
    private final ObjectMapper mapper;
    private final String publicBaseUrl;

    public ReceiveController(ReceiveService service, ReceiptStore store, ReceivePullExecutor executor, ObjectMapper mapper,
            FacadeProperties props) {
        this.service = service;
        this.store = store;
        this.executor = executor;
        this.mapper = mapper;
        String base = props.publicBaseUrl() == null ? "" : props.publicBaseUrl();
        this.publicBaseUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /**
     * The sender is answered as FHIR says a transaction is: HTTP 200 and a Bundle of type {@code transaction-response}
     * (HAPI's own), or, if the transaction fails, an OperationOutcome with the matching status (422 not a usable transaction
     * Bundle, 413 too large, 502 HAPI refused it) and nothing stored. The callback to the sender (the ARex pull) runs afterwards,
     * so a slow sender no longer makes the request time out. Where to read the result: the {@code Link} header (rel="receipt")
     * and {@code X-Receipt-Id}, then {@code GET /ode/receipts/{id}}, whose {@code state} is {@code pending} until the pull ends.
     *
     * <p>{@code ?wait=true} keeps the earlier behavior for organizers and scripts: wait for the pull and answer with the
     * finished receipt (plain JSON, not FHIR). The body is read with a hard cap before anything is parsed.
     */
    @PostMapping(value = "/ode/receive/{useCase}", consumes = {"application/json", "application/fhir+json"})
    public ResponseEntity<String> receive(@PathVariable String useCase, @RequestParam(defaultValue = "false") boolean wait,
            HttpServletRequest request) throws IOException {
        try {
            UseCase uc = UseCase.parse(useCase);
            byte[] body = request.getInputStream().readNBytes(ReceiveService.MAX_BODY_BYTES + 1);
            if (body.length > ReceiveService.MAX_BODY_BYTES) {
                throw new PayloadTooLargeException("The payload is larger than " + ReceiveService.MAX_BODY_BYTES + " bytes.");
            }
            String text = new String(body, StandardCharsets.UTF_8);
            if (wait) {
                return noStore(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)).body(mapper.writeValueAsString(service.receive(uc, text)));
            }
            ReceiveService.Accepted accepted = service.accept(uc, text);
            String id = accepted.pending().id();
            executor.run(() -> service.complete(accepted));
            return noStore(ResponseEntity.ok().contentType(FHIR_JSON)).header(HttpHeaders.LINK, "<" + publicBaseUrl + "/ode/receipts/" + id + ">; rel=\"receipt\"")
                    .header("X-Receipt-Id", id).body(accepted.transactionResponse());
        } catch (BadDataException e) {
            return outcome(HttpStatus.UNPROCESSABLE_ENTITY, "invalid", e.getMessage());
        } catch (PayloadTooLargeException e) {
            return outcome(HttpStatus.PAYLOAD_TOO_LARGE, "too-long", e.getMessage());
        } catch (UpstreamException e) {
            return outcome(HttpStatus.BAD_GATEWAY, "exception", e.getMessage());
        }
    }

    private ResponseEntity<String> outcome(HttpStatus status, String code, String text) {
        ObjectNode oo = mapper.createObjectNode();
        oo.put("resourceType", "OperationOutcome");
        ObjectNode issue = oo.putArray("issue").addObject();
        issue.put("severity", "error").put("code", code).put("diagnostics", text);
        return noStore(ResponseEntity.status(status).contentType(FHIR_JSON)).body(oo.toString());
    }

    @GetMapping("/ode/receipts")
    public ResponseEntity<List<Receipt>> recent(@RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String useCase) {
        String only = useCase == null || useCase.isBlank() ? null : UseCase.parse(useCase).id();
        int n = Math.max(1, Math.min(limit, 100));
        List<Receipt> all = store.recent(100);
        List<Receipt> shown = all.stream().filter(r -> only == null || only.equals(r.useCase())).limit(n).toList();
        return noStore(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)).body(shown);
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
        return ImageResponse.of(bytes, viewable, fileKey);
    }

    private static ResponseEntity.BodyBuilder noStore(ResponseEntity.BodyBuilder b) {
        return b.header(HttpHeaders.CACHE_CONTROL, "no-store");
    }
}
