package io.github.houseelves.github.elf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON the way the desk's Quarkus wrote it (times as ISO text, unknown fields ignored), so the tab gets what
 * DeskApi returned. Files are written whole, then moved into place: a reader never sees half of one.
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private Json() {
    }

    /** {@code value} as JSON in {@code file}, written next to it first and then moved into its place. */
    public static void write(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("." + file.getFileName() + ".tmp");
            Files.write(tmp, MAPPER.writeValueAsBytes(value));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't write " + file, e);
        }
    }

    /** What {@code file} holds, or {@code empty} when there's no such file yet. */
    public static <T> T read(Path file, TypeReference<T> type, T empty) {
        if (!Files.isRegularFile(file)) return empty;
        try {
            return MAPPER.readValue(file.toFile(), type);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't read " + file, e);
        }
    }

    public static <T> T read(Path file, Class<T> type, T empty) {
        if (!Files.isRegularFile(file)) return empty;
        try {
            return MAPPER.readValue(file.toFile(), type);
        } catch (IOException e) {
            throw new UncheckedIOException("Couldn't read " + file, e);
        }
    }
}
