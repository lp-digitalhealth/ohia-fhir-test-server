package org.ohia.facade.send;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;
import org.ohia.facade.config.FacadeProperties;
import org.springframework.stereotype.Component;

/** One JSON file per sent manifest under the data directory. */
@Component
public class ManifestStore {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9\\-.]{0,127}");

    private final Path dir;
    private final ObjectMapper mapper;

    public ManifestStore(FacadeProperties props, ObjectMapper mapper) {
        this.dir = Path.of(props.dataDir()).resolve("manifests");
        this.mapper = mapper;
    }

    public void save(ManifestRecord record) {
        try {
            Files.createDirectories(dir);
            Path target = file(record.manifestId());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.write(tmp, mapper.writeValueAsBytes(record));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Optional<ManifestRecord> load(String manifestId) {
        if (manifestId == null || !SAFE_ID.matcher(manifestId).matches()) {
            return Optional.empty();
        }
        Path f = file(manifestId);
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(f.toFile(), ManifestRecord.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path file(String manifestId) {
        if (!SAFE_ID.matcher(manifestId).matches()) {
            throw new IllegalArgumentException("Unsafe manifest id");
        }
        return dir.resolve(manifestId + ".json");
    }
}
