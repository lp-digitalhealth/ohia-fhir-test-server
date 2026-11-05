package org.ohia.facade.clients;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.ohia.facade.common.BadDataException;
import org.ohia.facade.config.FacadeProperties;
import org.springframework.stereotype.Component;

/**
 * The static tester registry. The organizer edits the file; the facade re-reads it when its
 * modification time changes, so adding a tester needs no restart. A missing file means nobody
 * is registered. A malformed file is refused as a whole (and reported), never half-applied.
 */
@Component
public class ClientRegistry {

    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern ORG_REF = Pattern.compile("Organization/[A-Za-z0-9][A-Za-z0-9.-]{0,63}");

    private final Path file;
    private final ObjectMapper mapper;
    private Map<String, ClientEntry> entries = Map.of();
    private FileTime loadedAt;

    private final boolean allowHttp;

    public ClientRegistry(FacadeProperties props, ObjectMapper mapper) {
        this.file = Path.of(props.clientsFile());
        this.mapper = mapper;
        this.allowHttp = props.send().allowHttp();
    }

    public synchronized Optional<ClientEntry> find(String clientId) {
        refreshIfChanged();
        return Optional.ofNullable(entries.get(clientId));
    }

    private void refreshIfChanged() {
        try {
            if (!Files.isRegularFile(file)) {
                entries = Map.of();
                loadedAt = null;
                return;
            }
            FileTime modified = Files.getLastModifiedTime(file);
            if (modified.equals(loadedAt)) {
                return;
            }
            entries = parse(mapper.readTree(file.toFile()));
            loadedAt = modified;
        } catch (IOException e) {
            throw new BadDataException("The tester registry file cannot be read: " + e.getMessage());
        }
    }

    private Map<String, ClientEntry> parse(JsonNode root) {
        Map<String, ClientEntry> parsed = new HashMap<>();
        for (JsonNode c : root.path("clients")) {
            String id = c.path("clientId").asText("");
            String org = c.path("requestingOrganization").asText("");
            if (!CLIENT_ID.matcher(id).matches()) {
                throw new BadDataException("Registry: invalid clientId '" + id + "'.");
            }
            if (!ORG_REF.matcher(org).matches()) {
                throw new BadDataException("Registry: client " + id + " needs requestingOrganization like Organization/<id>.");
            }
            List<String> prefixes = new ArrayList<>();
            for (JsonNode p : c.path("allowedTargetPrefixes")) {
                String prefix = p.asText("");
                if (!prefix.startsWith("https://") && !(allowHttp && prefix.startsWith("http://"))) {
                    throw new BadDataException("Registry: client " + id + " has a target prefix that is not https://: " + prefix);
                }
                prefixes.add(prefix);
            }
            if (parsed.put(id, new ClientEntry(id, org, List.copyOf(prefixes), c.path("jwks"))) != null) {
                throw new BadDataException("Registry: duplicate clientId " + id + ".");
            }
        }
        return parsed;
    }
}
