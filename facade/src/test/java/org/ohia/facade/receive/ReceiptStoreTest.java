package org.ohia.facade.receive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ohia.facade.config.FacadeProperties;

class ReceiptStoreTest {

    @TempDir
    Path dir;

    private ReceiptStore store() {
        return new ReceiptStore(new FacadeProperties("h", "p", "t", dir.toString(), "/c.json",
                new FacadeProperties.Seed("u", "r", "p"), new FacadeProperties.Send(false, false, 30),
                new FacadeProperties.Client("c", "/k", "Organization/o", "arex-manifest", 20, 1L, 1L, 5, 10)),
                new ObjectMapper().findAndRegisterModules());
    }

    private static Receipt receipt(String id, Instant at) {
        return new Receipt(id, at, "REF", "man", true, 3, "https://s/arex", "https://s/arex/auth/token", "arex-manifest", null,
                List.of(), List.of(), List.of());
    }

    @Test
    void savesAndReloadsAReceiptAndItsImage() {
        ReceiptStore s = store();
        String id = UUID.randomUUID().toString();
        s.saveImage(id, "photo-1", new byte[] {1, 2, 3});
        s.saveReceipt(receipt(id, Instant.parse("2026-11-05T10:00:00Z")));
        assertEquals("REF", s.load(id).orElseThrow().referralId());
        assertArrayEquals(new byte[] {1, 2, 3}, s.image(id, "photo-1").orElseThrow());
    }

    @Test
    void listsNewestFirstAndHonoursTheLimit() {
        ReceiptStore s = store();
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String c = UUID.randomUUID().toString();
        s.saveReceipt(receipt(a, Instant.parse("2026-11-05T10:00:00Z")));
        s.saveReceipt(receipt(b, Instant.parse("2026-11-05T12:00:00Z")));
        s.saveReceipt(receipt(c, Instant.parse("2026-11-05T11:00:00Z")));
        assertEquals(List.of(b, c, a), s.recent(10).stream().map(Receipt::id).toList());
        assertEquals(List.of(b, c), s.recent(2).stream().map(Receipt::id).toList());
    }

    /** Control: ids arrive in URLs and in a stranger's payload; none may reach the file system unchecked. */
    @Test
    void unsafeIdsNeverTouchTheFileSystem() {
        ReceiptStore s = store();
        String id = UUID.randomUUID().toString();
        for (String bad : new String[] {"../x", "a/b", "..", "a\\b", "", ".hidden", "x".repeat(65), "a b"}) {
            assertFalse(ReceiptStore.validEntryId(bad), bad);
            assertThrows(IllegalArgumentException.class, () -> s.saveImage(id, bad, new byte[] {1}), bad);
            assertTrue(s.image(id, bad).isEmpty(), bad);
        }
        for (String bad : new String[] {"../../etc", "not-a-uuid", "", "00000000-0000-0000-0000-00000000000g"}) {
            assertFalse(ReceiptStore.validReceiptId(bad), bad);
            assertTrue(s.load(bad).isEmpty(), bad);
        }
    }

    @Test
    void sanitizedNamesAreSafeAndNeverEmpty() {
        for (String raw : new String[] {"a/b", "../../etc/passwd", "photo 1", "", ".x", "x".repeat(200), "\u0000‮"}) {
            String safe = ReceiptStore.safeEntryName(raw);
            assertTrue(ReceiptStore.validEntryId(safe), raw + " -> " + safe);
        }
    }
}
