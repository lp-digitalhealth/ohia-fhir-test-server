package org.ohia.facade.eob;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.ohia.facade.config.FacadeProperties;
import org.springframework.stereotype.Component;

/**
 * The oral EOBs this server serves, as one file under the facade's data folder so they survive a restart. The set is
 * replaced as a whole, through a temporary file and an atomic move, so a reader never sees half of a new set. Nothing
 * here writes except {@link #replace}; reading never changes state.
 */
@Component
public class OralEobStore {

    /** One loaded set: the pinned data commit it came from, when it was loaded, and the resources in file order. */
    public record Snapshot(String ref, Instant loadedAt, List<JsonNode> resources) {
    }

    private final Path file;
    private final ObjectMapper mapper;
    private Snapshot snapshot;
    private boolean loaded;

    public OralEobStore(FacadeProperties props, ObjectMapper mapper) {
        this.file = Path.of(props.dataDir()).resolve("eob").resolve("oral-eobs.json");
        this.mapper = mapper;
    }

    /** The current set, or empty if nothing has been loaded yet. */
    public synchronized Optional<Snapshot> current() {
        if (!loaded) {
            snapshot = Files.isRegularFile(file) ? read() : null;
            loaded = true;
        }
        return Optional.ofNullable(snapshot);
    }

    public synchronized void replace(String ref, List<JsonNode> resources, Instant at) {
        ObjectNode root = mapper.createObjectNode();
        root.put("ref", ref);
        root.put("loadedAt", at.toString());
        ArrayNode array = root.putArray("resources");
        resources.forEach(array::add);
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writeValueAsString(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("The oral EOB set could not be saved: " + e.getMessage(), e);
        }
        snapshot = new Snapshot(ref, at, List.copyOf(resources));
        loaded = true;
    }

    private Snapshot read() {
        try {
            JsonNode root = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            List<JsonNode> resources = new ArrayList<>();
            root.path("resources").forEach(resources::add);
            return new Snapshot(root.path("ref").asText(""), Instant.parse(root.path("loadedAt").asText()), List.copyOf(resources));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("The saved oral EOB set " + file + " cannot be read: " + e.getMessage()
                    + ". Run POST /ode/admin/seed-eob to replace it.", e);
        }
    }
}
