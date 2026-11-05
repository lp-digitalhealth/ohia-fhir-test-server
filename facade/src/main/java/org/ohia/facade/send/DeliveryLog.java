package org.ohia.facade.send;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.ohia.facade.config.FacadeProperties;
import org.springframework.stereotype.Component;

/** Append-only JSON-lines log of sends, on the data volume so it survives restarts. */
@Component
public class DeliveryLog {

    private final Path file;
    private final ObjectMapper mapper;

    public DeliveryLog(FacadeProperties props, ObjectMapper mapper) {
        this.file = Path.of(props.dataDir()).resolve("log").resolve("sends.jsonl");
        this.mapper = mapper;
    }

    public synchronized void append(SendRecord record) {
        try {
            Files.createDirectories(file.getParent());
            String line = mapper.writeValueAsString(record) + "\n";
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The most recent records, newest first. */
    public synchronized List<SendRecord> recent(int limit) {
        List<SendRecord> all = readAll();
        List<SendRecord> out = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && out.size() < limit; i--) {
            out.add(all.get(i));
        }
        return out;
    }

    public synchronized Optional<SendRecord> find(String sendId) {
        return readAll().stream().filter(r -> r.sendId().equals(sendId)).findFirst();
    }

    private List<SendRecord> readAll() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            List<SendRecord> out = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    out.add(mapper.readValue(line, SendRecord.class));
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
