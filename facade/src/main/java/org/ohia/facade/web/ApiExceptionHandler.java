package org.ohia.facade.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.ohia.facade.arex.OAuthException;
import org.ohia.facade.common.BadDataException;
import org.ohia.facade.common.ForbiddenTargetException;
import org.ohia.facade.common.NotSeededException;
import org.ohia.facade.common.PayloadTooLargeException;
import org.ohia.facade.common.UpstreamException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BadDataException.class)
    ResponseEntity<Map<String, Object>> badData(BadDataException e) {
        return body(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), null);
    }

    @ExceptionHandler(OAuthException.class)
    ResponseEntity<Map<String, Object>> oauth(OAuthException e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", e.error());
        m.put("error_description", e.getMessage());
        return ResponseEntity.status(e.httpStatus()).header("Cache-Control", "no-store").header("Pragma", "no-cache").body(m);
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ResponseEntity<Map<String, Object>> tooLarge(PayloadTooLargeException e) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage(), null);
    }

    @ExceptionHandler(ForbiddenTargetException.class)
    ResponseEntity<Map<String, Object>> forbiddenTarget(ForbiddenTargetException e) {
        return body(HttpStatus.FORBIDDEN, e.getMessage(), null);
    }

    @ExceptionHandler(NotSeededException.class)
    ResponseEntity<Map<String, Object>> notSeeded(NotSeededException e) {
        return body(HttpStatus.CONFLICT, e.getMessage(), null);
    }

    @ExceptionHandler(UpstreamException.class)
    ResponseEntity<Map<String, Object>> upstream(UpstreamException e) {
        return body(HttpStatus.BAD_GATEWAY, e.getMessage(), e.upstreamStatus() >= 0 ? e.upstreamStatus() : null);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String message, Integer upstream) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        if (upstream != null) {
            m.put("upstreamStatus", upstream);
        }
        return ResponseEntity.status(status).body(m);
    }
}
