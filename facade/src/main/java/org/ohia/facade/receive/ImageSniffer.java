package org.ohia.facade.receive;

/**
 * Decides, from the bytes themselves, whether something is a JPEG or a PNG. The declared content
 * type comes from a stranger's payload and is never trusted: only bytes that really start like a
 * JPEG or PNG may ever be served inline, so a sender cannot plant an HTML or SVG file that would
 * run on our origin.
 */
public final class ImageSniffer {

    private ImageSniffer() {
    }

    /** "image/jpeg", "image/png", or null if the bytes are neither. */
    public static String sniff(byte[] b) {
        if (b == null) {
            return null;
        }
        if (b.length >= 3 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8 && (b[2] & 0xff) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "image/png";
        }
        return null;
    }
}
