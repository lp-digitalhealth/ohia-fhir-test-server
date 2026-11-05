package org.ohia.facade.receive;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.ohia.facade.config.FacadeProperties;
import org.springframework.stereotype.Component;

/**
 * Receipts and the verified images, one folder per receipt under {@code <data dir>/received/}.
 * Files are written through a temporary file and an atomic move. Ids that reach the file system
 * are checked first, because they arrive in URLs and in a stranger's payload.
 */
@Component
public class ReceiptStore {

    private static final Pattern RECEIPT_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern ENTRY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private final Path root;
    private final ObjectMapper mapper;

    public ReceiptStore(FacadeProperties props, ObjectMapper mapper) {
        this.root = Path.of(props.dataDir()).resolve("received");
        this.mapper = mapper;
    }

    public static boolean validReceiptId(String id) {
        return id != null && RECEIPT_ID.matcher(id).matches();
    }

    public static boolean validEntryId(String id) {
        return id != null && ENTRY_ID.matcher(id).matches();
    }

    /** A usable image file name for an entry id that is not safe to use as a file name. */
    public static String safeEntryName(String entryId) {
        String cleaned = entryId == null ? "" : entryId.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.isEmpty() || !Character.isLetterOrDigit(cleaned.charAt(0))) {
            cleaned = "x" + cleaned;
        }
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    public void saveImage(String receiptId, String fileKey, byte[] bytes) {
        write(imageFile(receiptId, fileKey), bytes);
    }

    public void saveReceipt(Receipt receipt) {
        try {
            write(dir(receipt.id()).resolve("receipt.json"), mapper.writeValueAsBytes(receipt));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<Receipt> load(String id) {
        if (!validReceiptId(id)) {
            return Optional.empty();
        }
        Path f = dir(id).resolve("receipt.json");
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(f.toFile(), Receipt.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<byte[]> image(String receiptId, String fileKey) {
        if (!validReceiptId(receiptId) || !validEntryId(fileKey)) {
            return Optional.empty();
        }
        Path f = imageFile(receiptId, fileKey);
        try {
            return Files.isRegularFile(f) ? Optional.of(Files.readAllBytes(f)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Newest first. */
    public List<Receipt> recent(int limit) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(root)) {
            List<Receipt> all = new ArrayList<>();
            for (Path d : (Iterable<Path>) dirs.filter(Files::isDirectory)::iterator) {
                load(d.getFileName().toString()).ifPresent(all::add);
            }
            all.sort(Comparator.comparing(Receipt::receivedAt).reversed());
            return all.size() > limit ? all.subList(0, limit) : all;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path dir(String receiptId) {
        if (!validReceiptId(receiptId)) {
            throw new IllegalArgumentException("Unsafe receipt id");
        }
        return root.resolve(receiptId);
    }

    /** {@code fileKey} must already be a safe name (the service assigns it); anything else is refused outright. */
    private Path imageFile(String receiptId, String fileKey) {
        if (!validEntryId(fileKey)) {
            throw new IllegalArgumentException("Unsafe image key");
        }
        return dir(receiptId).resolve("images").resolve(fileKey + ".bin");
    }

    private static void write(Path target, byte[] data) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
