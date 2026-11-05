package org.ohia.facade.receive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.ohia.facade.Uc04aFixture;

class ImageSnifferTest {

    @Test
    void recognizesRealJpegAndPngBytes() {
        assertEquals("image/jpeg", ImageSniffer.sniff(Uc04aFixture.resource("intraoral-photo-1.jpg")));
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
        assertEquals("image/png", ImageSniffer.sniff(png));
    }

    /** Control: nothing that could run as a page may ever look like an image. */
    @Test
    void refusesHtmlSvgScriptEmptyAndTruncatedBytes() {
        for (String s : new String[] {"<html><script>alert(1)</script></html>", "<svg xmlns='http://www.w3.org/2000/svg' onload='alert(1)'/>",
                "GIF89a", "%PDF-1.7", ""}) {
            assertNull(ImageSniffer.sniff(s.getBytes(StandardCharsets.UTF_8)), s);
        }
        assertNull(ImageSniffer.sniff(null));
        assertNull(ImageSniffer.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8}), "a JPEG needs all three marker bytes");
        assertNull(ImageSniffer.sniff(new byte[] {(byte) 0x89, 'P', 'N', 'G'}), "a PNG needs the full 8-byte signature");
    }

    @Test
    void aJpegDeclaredAsHtmlIsStillJustBytesAndANonImageDeclaredAsJpegIsNot() {
        assertEquals("image/jpeg", ImageSniffer.sniff(Uc04aFixture.resource("intraoral-photo-2.jpg")));
        assertNull(ImageSniffer.sniff("<html>".getBytes(StandardCharsets.UTF_8)));
    }
}
