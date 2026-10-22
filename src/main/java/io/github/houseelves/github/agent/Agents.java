package io.github.houseelves.github.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.houseelves.github.elf.Json;

/**
 * The elf's agents, which the runner runs for it. Starting one is the action's answer ({@code {"agent": ...}},
 * see {@link #start}); the runner reports in {@code agents/<id>.json}: {id, status, lines, output, session, git,
 * cost, files, error, created, started, finished}, with the agent's files next to it as {@code <id>--<name>}.
 * When one ends, the runner calls the elf's agent-done action, which takes the report in
 * ({@link io.github.houseelves.github.thread.ThreadService#agentDone}).
 */
public class Agents {

    /** The runner's rule for an agent's id. */
    public static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,79}");

    private final Path dir;
    private ObjectNode started;
    private String cancelled;

    public Agents(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    /** The runner's report on an agent, or null when there is none (yet). */
    public JsonNode report(String id) {
        if (id == null || !ID.matcher(id).matches()) return null;
        Path f = dir.resolve(id + ".json");
        try {
            return Files.isRegularFile(f) ? Json.MAPPER.readTree(f.toFile()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** What an agent said so far, from its report. */
    public List<String> lines(String id) {
        JsonNode r = report(id);
        List<String> out = new ArrayList<>();
        if (r != null) r.path("lines").forEach(l -> out.add(l.asText()));
        return List.copyOf(out);
    }

    /**
     * An agent to start: {id, checkout, prompt, untrusted, schema, session}. The action answers with it as
     * {@code {"agent": ...}}, and the runner starts it in its place (what the desk's job.start was).
     */
    public void start(ObjectNode request) {
        started = request;
    }

    /** The agent this run asked for, or null. */
    public ObjectNode started() {
        return started;
    }

    /** An agent to stop: the action answers {@code {"cancelAgent": id}}, and the tab asks the runner to stop it. */
    public void cancel(String id) {
        cancelled = id;
    }

    public String cancelled() {
        return cancelled;
    }

    /** An agent's id for an item: its key in [a-z0-9-], and when, e.g. quarkusio-quarkus-50087-1761100000000. */
    public static String newId(String itemKey, long millis) {
        String slug = itemKey.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) slug = "item";
        if (slug.length() > 60) slug = slug.substring(0, 60).replaceAll("-+$", "");
        return slug + "-" + millis;
    }
}
