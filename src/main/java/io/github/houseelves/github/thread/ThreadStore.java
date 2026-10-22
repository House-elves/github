package io.github.houseelves.github.thread;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;

import io.github.houseelves.github.elf.Home;
import io.github.houseelves.github.elf.Json;

/**
 * The desk's thread, turn, draft, job, job_file and github_action tables, as files: one per thread in
 * {@code state/threads/<key>.json} (the thread with its turns, drafts, jobs and files), and the audit of GitHub
 * writes in {@code state/actions.json}. Everything is read once per run and written back whole when it changes;
 * the elf runs one action at a time (see {@link io.github.houseelves.github.elf.ElfLock}).
 */
public class ThreadStore {

    /** One thread's file. {@code squashing}: a squash handed to the runner, which {@link ThreadService#squashDone} finishes. */
    public static class Record {
        public DeskThread thread;
        public List<Turn> turns = new ArrayList<>();
        public List<Draft> drafts = new ArrayList<>();
        public List<Job> jobs = new ArrayList<>();
        public List<JobFile> files = new ArrayList<>();
        public Squashing squashing;
    }

    /** A squash handed to the runner: where its push will go, and when it was asked for. */
    public record Squashing(String headRepo, String headRef, Instant at) {
    }

    private final Path dir;
    private final Path actionsFile;
    private Map<Long, Record> records;
    private List<GitHubAction> actions;
    /** The threads written during this run: their views (threads/) are written again. */
    private final java.util.Set<Long> saved = new java.util.LinkedHashSet<>();

    public ThreadStore(Path stateDir) {
        this.dir = stateDir.resolve("threads");
        this.actionsFile = stateDir.resolve("actions.json");
    }

    private synchronized Map<Long, Record> records() {
        if (records == null) {
            records = new LinkedHashMap<>();
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path f : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                        Record r = Json.read(f, Record.class, null);
                        if (r != null && r.thread != null) records.put(r.thread.id, r);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
        return records;
    }

    /** Writes one thread's file, whole. */
    public synchronized void save(long threadId) {
        Record r = records().get(threadId);
        if (r != null) Json.write(dir.resolve(Home.fileName(r.thread.itemKey) + ".json"), r);
        saved.add(threadId);
    }

    /** The threads written during this run. */
    public synchronized List<DeskThread> saved() {
        return saved.stream().map(this::thread).filter(Objects::nonNull).toList();
    }

    public Record record(long threadId) {
        return records().get(threadId);
    }

    // ---- threads ----

    public List<DeskThread> threads() {
        return records().values().stream().map(r -> r.thread).toList();
    }

    public DeskThread byKey(String itemKey) {
        return records().values().stream().map(r -> r.thread).filter(t -> t.itemKey.equals(itemKey)).findFirst().orElse(null);
    }

    public DeskThread thread(Long id) {
        Record r = id == null ? null : records().get(id);
        return r == null ? null : r.thread;
    }

    /** A new thread (it gets its id), or a thread from the desk's database (it keeps its id). */
    public synchronized void add(DeskThread t) {
        if (t.id == null) t.id = next(r -> Stream.of(r.thread.id));
        Record r = new Record();
        r.thread = t;
        records().put(t.id, r);
        save(t.id);
    }

    // ---- turns ----

    public synchronized Turn addTurn(long threadId, String role, String action, String text) {
        Turn t = new Turn();
        t.threadId = threadId;
        t.role = role;
        t.action = action;
        t.text = text;
        add(t);
        return t;
    }

    public synchronized void add(Turn t) {
        if (t.id == null) t.id = next(r -> r.turns.stream().map(x -> x.id));
        records().get(t.threadId).turns.add(t);
        save(t.threadId);
    }

    public List<Turn> turns(long threadId) {
        Record r = records().get(threadId);
        return r == null ? List.of() : r.turns.stream().sorted(Comparator.comparing(x -> x.id)).toList();
    }

    // ---- drafts ----

    public synchronized void add(Draft d) {
        if (d.id == null) d.id = next(r -> r.drafts.stream().map(x -> x.id));
        records().get(d.threadId).drafts.add(d);
        save(d.threadId);
    }

    public Draft draft(Long id) {
        if (id == null) return null;
        return records().values().stream().flatMap(r -> r.drafts.stream()).filter(d -> d.id.equals(id)).findFirst().orElse(null);
    }

    public List<Draft> drafts(long threadId) {
        Record r = records().get(threadId);
        return r == null ? List.of() : r.drafts.stream().sorted(Comparator.comparing(x -> x.id)).toList();
    }

    /** The draft waiting for you in a thread, if any (only the newest one is kept pending). */
    public Draft pendingDraft(long threadId) {
        return drafts(threadId).stream().filter(d -> d.state.equals(Draft.PENDING)).reduce((a, b) -> b).orElse(null);
    }

    /** Every pending draft of a thread becomes discarded: a newer one takes its place. */
    public void discardPending(long threadId) {
        drafts(threadId).stream().filter(d -> d.state.equals(Draft.PENDING)).forEach(d -> d.state = Draft.DISCARDED);
    }

    // ---- jobs ----

    public synchronized void add(Job j) {
        if (j.id == null) j.id = next(r -> r.jobs.stream().map(x -> x.id));
        records().get(j.threadId).jobs.add(j);
        save(j.threadId);
    }

    public Job job(Long id) {
        if (id == null) return null;
        return allJobs().filter(j -> j.id.equals(id)).findFirst().orElse(null);
    }

    public Job byAgent(String agent) {
        return allJobs().filter(j -> agent.equals(j.agent)).findFirst().orElse(null);
    }

    public List<Job> jobs(long threadId) {
        Record r = records().get(threadId);
        return r == null ? List.of() : r.jobs.stream().sorted(Comparator.comparing(x -> x.id)).toList();
    }

    public Job openJob(long threadId) {
        return jobs(threadId).stream().filter(Job::open).findFirst().orElse(null);
    }

    /** The thread's most recent job, open or not, or null. */
    public Job latestJob(long threadId) {
        return jobs(threadId).stream().reduce((a, b) -> b).orElse(null);
    }

    /** The last finished self-review or self-review fix: whether the review's fixes are still to be made. */
    public Job lastReview(long threadId) {
        return jobs(threadId).stream().filter(j -> j.state.equals(Job.DONE) && List.of("review", "self-fix").contains(j.action))
                .reduce((a, b) -> b).orElse(null);
    }

    public List<Job> jobsIn(String state) {
        return allJobs().filter(j -> j.state.equals(state)).sorted(Comparator.comparing(x -> x.id)).toList();
    }

    private Stream<Job> allJobs() {
        return records().values().stream().flatMap(r -> r.jobs.stream());
    }

    // ---- files ----

    public synchronized void add(JobFile f) {
        Job j = job(f.jobId);
        if (f.id == null) f.id = next(r -> r.files.stream().map(x -> x.id));
        records().get(j.threadId).files.add(f);
        save(j.threadId);
    }

    public List<JobFile> files(long jobId) {
        return records().values().stream().flatMap(r -> r.files.stream()).filter(f -> f.jobId.equals(jobId))
                .sorted(Comparator.comparing(f -> f.name)).toList();
    }

    // ---- the audit of GitHub writes ----

    private synchronized List<GitHubAction> actionsList() {
        if (actions == null) actions = new ArrayList<>(Json.read(actionsFile, new TypeReference<List<GitHubAction>>() {}, List.of()));
        return actions;
    }

    public synchronized void audit(GitHubAction a) {
        if (a.id == null) a.id = actionsList().stream().map(x -> x.id).filter(Objects::nonNull).mapToLong(x -> x).max().orElse(0) + 1;
        actionsList().add(a);
        Json.write(actionsFile, actionsList());
    }

    public List<GitHubAction> actions() {
        return List.copyOf(actionsList());
    }

    /** The next id after every one in use, across all threads. */
    private long next(Function<Record, Stream<Long>> ids) {
        return records().values().stream().flatMap(ids).filter(Objects::nonNull).mapToLong(x -> x).max().orElse(0) + 1;
    }
}
