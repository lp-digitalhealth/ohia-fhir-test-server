package org.ohia.facade.web;

import org.ohia.facade.receive.ImageSniffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * How image bytes leave this server for the page. The bytes are shown inline ONLY if they really are a JPEG or a
 * PNG, decided from the bytes themselves and never from a declared type. Anything else is a forced download,
 * so no file that arrived from outside can ever run as a page on this origin.
 */
final class ImageResponse {

    private ImageResponse() {
    }

    /** @param inlineAllowed false forces a download even for real image bytes (the caller does not trust the object) */
    static ResponseEntity<byte[]> of(byte[] bytes, boolean inlineAllowed, String downloadName) {
        String sniffed = ImageSniffer.sniff(bytes);
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff");
        if (inlineAllowed && sniffed != null) {
            return b.contentType(MediaType.parseMediaType(sniffed)).header(HttpHeaders.CONTENT_DISPOSITION, "inline").body(bytes);
        }
        return b.contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName + ".bin\"").body(bytes);
    }
}
