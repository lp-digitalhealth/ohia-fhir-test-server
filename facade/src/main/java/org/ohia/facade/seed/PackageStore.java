package org.ohia.facade.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.ohia.facade.common.UseCase;
import org.ohia.facade.config.FacadeProperties;
import org.springframework.stereotype.Component;

/**
 * The facade's own files: one package index per use case, and the original ARex payload bytes. Payloads are
 * kept in one folder keyed by entry id; the two use cases' entry ids differ (radiograph-*, photo-*).
 */
@Component
public class PackageStore {

    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9\\-.]{0,63}");

    private final Path root;
    private final ObjectMapper mapper;

    public PackageStore(FacadeProperties props, ObjectMapper mapper) {
        this.root = Path.of(props.dataDir());
        this.mapper = mapper;
    }

    private Path indexFile(UseCase useCase) {
        return root.resolve("package").resolve(useCase.id() + "-i1.json");
    }

    private Path payloadFile(String entryId) {
        if (!SAFE_NAME.matcher(entryId).matches()) {
            throw new IllegalArgumentException("Unsafe payload name: " + entryId);
        }
        return root.resolve("arex-payloads").resolve(entryId + ".bin");
    }

    /** Writes the payloads first and the index last, each through a temp file and an atomic move. */
    public void save(PackageIndex index, Map<String, byte[]> payloadsByEntryId) {
        save(UseCase.UC07, index, payloadsByEntryId);
    }

    public void save(UseCase useCase, PackageIndex index, Map<String, byte[]> payloadsByEntryId) {
        try {
            Files.createDirectories(root.resolve("package"));
            Files.createDirectories(root.resolve("arex-payloads"));
            for (Map.Entry<String, byte[]> p : payloadsByEntryId.entrySet()) {
                writeAtomic(payloadFile(p.getKey()), p.getValue());
            }
            writeAtomic(indexFile(useCase), mapper.writeValueAsBytes(index));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<PackageIndex> load() {
        return load(UseCase.UC07);
    }

    public Optional<PackageIndex> load(UseCase useCase) {
        Path f = indexFile(useCase);
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(f.toFile(), PackageIndex.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<byte[]> payload(String entryId) {
        try {
            Path f = payloadFile(entryId);
            return Files.isRegularFile(f) ? Optional.of(Files.readAllBytes(f)) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeAtomic(Path target, byte[] data) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
