package io.github.houseelves.github.thread;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A file the agent left for you (a screenshot, a log). The runner keeps it next to the agent's report as
 * {@code agents/<id>--<name>} ({@code file}); this is what the thread shows of it.
 */
public class JobFile {

    /** A plain name: no path, no leading dot. */
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    /** The types kept, by extension. Images show inline; the rest only download (never rendered by the desk). */
    static final Map<String, String> TYPES = Map.ofEntries(Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
            Map.entry("txt", "text/plain"), Map.entry("md", "text/plain"), Map.entry("log", "text/plain"),
            Map.entry("csv", "text/plain"), Map.entry("json", "text/plain"), Map.entry("diff", "text/plain"),
            Map.entry("patch", "text/plain"));
    static final int MAX_SIZE = 10 * 1024 * 1024, MAX_PER_JOB = 20;

    public Long id;

    public Long jobId;

    public String name;

    public String contentType;

    public int size;

    /** Its name in the agents folder: {@code <agent id>--<name>}. */
    public String file;

    public Instant created = Instant.now();

    /** The type for this name, or null when it isn't one kept. */
    public static String typeOf(String name) {
        if (name == null || !NAME.matcher(name).matches() || !name.contains(".")) return null;
        return TYPES.get(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
    }

    /** What a thread shows of a job's files. {@code file}: where the tab reads it, in the agents folder. */
    public record Info(long id, String name, String type, int size, String file) {
    }

    public Info info() {
        return new Info(id, name, contentType, size, file);
    }
}
