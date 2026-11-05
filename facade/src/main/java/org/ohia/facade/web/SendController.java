package org.ohia.facade.web;

import java.util.List;
import org.ohia.facade.send.DeliveryLog;
import org.ohia.facade.send.SendRecord;
import org.ohia.facade.send.SendService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Organizer endpoints for sending. Protected by the shared S1 token like the rest of /ode/**. */
@RestController
public class SendController {

    private final SendService sendService;
    private final DeliveryLog log;

    public SendController(SendService sendService, DeliveryLog log) {
        this.sendService = sendService;
        this.log = log;
    }

    /**
     * Sends the UC07 referral to a registered tester. Answers 200 with the delivery outcome even
     * when the tester's server refuses it (outcome rejected-by-target) or cannot be reached
     * (unreachable), because that is a result of the test, not an error of this request.
     */
    @PostMapping("/ode/send")
    public SendRecord send(@RequestBody SendService.SendRequest request) {
        return sendService.send(request);
    }

    @GetMapping("/ode/sends")
    public List<SendRecord> recent(@RequestParam(defaultValue = "20") int limit) {
        return log.recent(Math.max(1, Math.min(limit, 100)));
    }

    @GetMapping("/ode/sends/{sendId}")
    public ResponseEntity<SendRecord> one(@PathVariable String sendId) {
        return log.find(sendId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
