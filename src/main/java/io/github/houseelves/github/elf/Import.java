package io.github.houseelves.github.elf;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;

import io.github.houseelves.github.ignore.IgnoredItem;
import io.github.houseelves.github.merge.MergeWatch;
import io.github.houseelves.github.scope.WatchScope;
import io.github.houseelves.github.thread.DeskThread;
import io.github.houseelves.github.thread.Draft;
import io.github.houseelves.github.thread.GitHubAction;
import io.github.houseelves.github.thread.Job;
import io.github.houseelves.github.thread.JobFile;
import io.github.houseelves.github.thread.ThreadStore;
import io.github.houseelves.github.thread.Turn;

/**
 * Moves the person's data in from the desk's H2 file (desk.mv.db): its threads with their turns, drafts, jobs and
 * files, the audit of GitHub writes, merge watches, ignored items, the scope and the settings, as the elf's files.
 * Agents that were still open become failed: the desk can't hand them over. Re-runnable: a thread file newer than
 * what the database has for it stays as it is, and lists already in the elf keep their own rows.
 *
 * <p>
 * Run it with the desk stopped (H2 lets one program open the file), or on a copy of the file.
 */
public final class Import {

    static final String MOVED = "The desk moved this to the GitHub elf";

    private Import() {
    }

    /** What was moved in, for the person running it. */
    public record Summary(int threads, int skipped, int jobsFailed, int files, int actions, int merges, int ignored,
            boolean scope, boolean settings) {

        @Override
        public String toString() {
            return "Moved in " + threads + " threads" + (skipped > 0 ? " (" + skipped + " left as they were: newer here)" : "")
                    + ", " + files + " agent files, " + actions + " GitHub writes, " + merges + " merges, " + ignored
                    + " ignored items" + (scope ? ", the scope" : "") + (settings ? ", the settings" : "")
                    + (jobsFailed > 0 ? ". " + jobsFailed + " agents that were still working are marked failed" : "") + ".";
        }
    }

    public static Summary run(Home home, Path db) throws Exception {
        String file = db.toAbsolutePath().toString().replaceFirst("\\.mv\\.db$", "");
        if (!Files.isRegularFile(Path.of(file + ".mv.db"))) throw new IOException("There's no H2 database at " + db);
        try (Connection c = DriverManager.getConnection("jdbc:h2:file:" + file + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r", "desk", "")) {
            return run(home, c);
        }
    }

    static Summary run(Home home, Connection c) throws Exception {
        Path state = home.state();
        Files.createDirectories(state);
        ThreadStore store = new ThreadStore(state);
        // Ids the elf already uses stay its own: the desk's come after them.
        long offset = maxId(store);

        Map<Long, ThreadStore.Record> records = new LinkedHashMap<>();
        Map<Long, Instant> newest = new HashMap<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "select id, item_key, item_kind, title, url, claude_session, created, updated, seen from thread order by id")) {
            while (r.next()) {
                DeskThread t = new DeskThread();
                t.id = r.getLong("id") + offset;
                t.itemKey = r.getString("item_key");
                t.itemKind = r.getString("item_kind");
                t.title = r.getString("title");
                t.url = r.getString("url");
                t.claudeSession = r.getString("claude_session");
                t.created = instant(r, "created");
                t.updated = instant(r, "updated");
                t.seen = instant(r, "seen");
                ThreadStore.Record rec = new ThreadStore.Record();
                rec.thread = t;
                records.put(r.getLong("id"), rec);
                newest.put(r.getLong("id"), t.updated);
            }
        }
        int failed = 0;
        Map<Long, String> agentOf = new HashMap<>();
        List<Turn> notes = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "select id, thread_id, action, prompt, checkout, untrusted, state, error, fixes, cost_usd, created, started, finished "
                        + "from job order by id")) {
            while (r.next()) {
                ThreadStore.Record rec = records.get(r.getLong("thread_id"));
                if (rec == null) continue;
                Job j = new Job();
                j.id = r.getLong("id") + offset;
                j.agent = "desk-" + j.id;
                j.threadId = rec.thread.id;
                j.action = r.getString("action");
                j.prompt = r.getString("prompt");
                j.checkout = r.getString("checkout");
                j.untrusted = r.getString("untrusted");
                j.state = r.getString("state");
                j.error = r.getString("error");
                j.fixes = (Integer) r.getObject("fixes");
                BigDecimal cost = r.getBigDecimal("cost_usd");
                j.costUsd = cost;
                j.created = instant(r, "created");
                j.started = instant(r, "started");
                j.finished = instant(r, "finished");
                newest.merge(r.getLong("thread_id"), latest(j.created, j.finished), Import::max);
                if (j.open()) {
                    j.state = Job.FAILED;
                    j.error = MOVED;
                    j.finished = Instant.now();
                    failed++;
                    Turn note = new Turn();
                    note.threadId = rec.thread.id;
                    note.role = Turn.SYSTEM;
                    note.text = "The agent stopped: " + MOVED + ".";
                    note.created = j.finished;
                    notes.add(note);
                }
                rec.jobs.add(j);
                agentOf.put(r.getLong("id"), j.agent);
            }
        }
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "select id, thread_id, role, action, text, created, job_id from turn order by id")) {
            while (r.next()) {
                ThreadStore.Record rec = records.get(r.getLong("thread_id"));
                if (rec == null) continue;
                Turn t = new Turn();
                t.id = r.getLong("id") + offset;
                t.threadId = rec.thread.id;
                t.role = r.getString("role");
                t.action = r.getString("action");
                t.text = r.getString("text");
                t.created = instant(r, "created");
                Object job = r.getObject("job_id");
                t.jobId = job == null ? null : ((Number) job).longValue() + offset;
                rec.turns.add(t);
                newest.merge(r.getLong("thread_id"), t.created, Import::max);
            }
        }
        // The notes on agents the desk couldn't hand over come after every turn.
        long turnId = records.values().stream().flatMap(x -> x.turns.stream()).mapToLong(x -> x.id).max().orElse(offset);
        for (Turn note : notes) {
            note.id = ++turnId;
            records.values().stream().filter(x -> x.thread.id.equals(note.threadId)).findFirst().ifPresent(x -> x.turns.add(note));
        }
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "select id, thread_id, job_id, kind, payload, state, posted_url, posted_at, created from draft order by id")) {
            while (r.next()) {
                ThreadStore.Record rec = records.get(r.getLong("thread_id"));
                if (rec == null) continue;
                Draft d = new Draft();
                d.id = r.getLong("id") + offset;
                d.threadId = rec.thread.id;
                Object job = r.getObject("job_id");
                d.jobId = job == null ? null : ((Number) job).longValue() + offset;
                d.kind = r.getString("kind");
                d.payload = r.getString("payload");
                d.state = r.getString("state");
                d.postedUrl = r.getString("posted_url");
                d.postedAt = instant(r, "posted_at");
                d.created = instant(r, "created");
                rec.drafts.add(d);
                newest.merge(r.getLong("thread_id"), latest(d.created, d.postedAt), Import::max);
            }
        }
        // The agents' files go where the runner keeps an elf's: agents/<id>--<name>.
        int files = 0;
        Map<Long, Long> threadOfJob = new HashMap<>();
        records.forEach((id, rec) -> rec.jobs.forEach(j -> threadOfJob.put(j.id - offset, id)));
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "select id, job_id, name, content_type, size, data, created from job_file order by id")) {
            while (r.next()) {
                Long threadId = threadOfJob.get(r.getLong("job_id"));
                if (threadId == null) continue;
                String agent = agentOf.get(r.getLong("job_id"));
                String name = r.getString("name");
                if (JobFile.typeOf(name) == null) continue;
                JobFile f = new JobFile();
                f.id = r.getLong("id") + offset;
                f.jobId = r.getLong("job_id") + offset;
                f.name = name;
                f.contentType = r.getString("content_type");
                f.size = r.getInt("size");
                f.file = agent + "--" + name;
                f.created = instant(r, "created");
                Path to = home.agents().resolve(f.file);
                if (!Files.exists(to)) {
                    Files.createDirectories(home.agents());
                    Files.write(to, r.getBytes("data"));
                }
                records.get(threadId).files.add(f);
                files++;
            }
        }

        int threads = 0, skipped = 0;
        Path dir = state.resolve("threads");
        for (var e : records.entrySet()) {
            ThreadStore.Record rec = e.getValue();
            Path f = dir.resolve(Home.fileName(rec.thread.itemKey) + ".json");
            Instant db = newest.get(e.getKey());
            if (Files.exists(f) && db != null && Files.getLastModifiedTime(f).toInstant().isAfter(db)) {
                skipped++;
                continue;
            }
            // A thread the elf already had under another id: the desk's copy takes its place.
            ThreadStore.Record mine = Json.read(f, ThreadStore.Record.class, null);
            if (mine != null && mine.thread != null) rec.thread.seen = latest(rec.thread.seen, mine.thread.seen);
            Json.write(f, rec);
            threads++;
        }

        int actions = mergeList(state.resolve("actions.json"), new TypeReference<List<GitHubAction>>() {}, rows(c,
                "select id, thread_id, draft_id, kind, request, result_url, login, at from github_action order by id", r -> {
                    GitHubAction a = new GitHubAction();
                    a.id = r.getLong("id") + offset;
                    Object t = r.getObject("thread_id"), d = r.getObject("draft_id");
                    a.threadId = t == null ? null : ((Number) t).longValue() + offset;
                    a.draftId = d == null ? null : ((Number) d).longValue() + offset;
                    a.kind = r.getString("kind");
                    a.request = r.getString("request");
                    a.resultUrl = r.getString("result_url");
                    a.login = r.getString("login");
                    a.at = instant(r, "at");
                    return a;
                }), a -> a.kind + " " + a.at + " " + a.request);
        int merges = mergeList(state.resolve("merges.json"), new TypeReference<List<MergeWatch>>() {}, rows(c,
                "select id, item_key, method, head_sha, state, outcome, login, created, checked, finished, require_approval "
                        + "from merge_watch order by id", r -> {
                    MergeWatch w = new MergeWatch();
                    w.id = r.getLong("id") + offset;
                    w.itemKey = r.getString("item_key");
                    w.method = r.getString("method");
                    w.headSha = r.getString("head_sha");
                    w.state = r.getString("state");
                    w.outcome = r.getString("outcome");
                    w.login = r.getString("login");
                    w.created = instant(r, "created");
                    w.checked = instant(r, "checked");
                    w.finished = instant(r, "finished");
                    w.requireApproval = r.getBoolean("require_approval");
                    return w;
                }), w -> w.itemKey + " " + w.created);
        int ignored = mergeList(state.resolve("ignored.json"), new TypeReference<List<IgnoredItem>>() {}, rows(c,
                "select item_key, login, ignored, returned_at, returned_reason from ignored_item", r -> {
                    IgnoredItem i = new IgnoredItem();
                    i.itemKey = r.getString("item_key");
                    i.login = r.getString("login");
                    i.ignored = instant(r, "ignored");
                    i.returnedAt = instant(r, "returned_at");
                    i.returnedReason = r.getString("returned_reason");
                    return i;
                }), i -> i.itemKey);

        // The board's settings and scope: only when the elf has none of its own yet.
        boolean scope = false, settings = false;
        if (!Files.exists(state.resolve("scope.json"))) {
            Json.write(state.resolve("scope.json"), rows(c, "select id, kind, name, mode, added from watch_scope order by id", r -> {
                WatchScope w = new WatchScope();
                w.id = r.getLong("id");
                w.kind = WatchScope.Kind.valueOf(r.getString("kind"));
                w.name = r.getString("name");
                w.mode = WatchScope.Mode.valueOf(r.getString("mode"));
                w.added = instant(r, "added");
                return w;
            }));
            scope = true;
        }
        if (!Files.exists(state.resolve("settings.json"))) {
            Map<String, String> values = new TreeMap<>();
            for (String[] kv : rows(c, "select setting_key, setting_value from setting", r -> new String[] { r.getString(1), r.getString(2) })) {
                values.put(kv[0], kv[1]);
            }
            Json.write(state.resolve("settings.json"), values);
            settings = true;
        }
        return new Summary(threads, skipped, failed, files, actions, merges, ignored, scope, settings);
    }

    /** The biggest id the elf already uses, of any kind: 0 when it has none. */
    private static long maxId(ThreadStore store) {
        return store.threads().stream().flatMap(t -> {
            ThreadStore.Record r = store.record(t.id);
            return Stream.of(Stream.of(r.thread.id), r.turns.stream().map(x -> x.id), r.drafts.stream().map(x -> x.id),
                    r.jobs.stream().map(x -> x.id), r.files.stream().map(x -> x.id)).flatMap(x -> x);
        }).filter(Objects::nonNull).mapToLong(x -> x).max().orElse(0);
    }

    /** The rows the elf doesn't have yet (the same {@code id}: what, and when) added to the list in {@code file}. */
    private static <T> int mergeList(Path file, TypeReference<List<T>> type, List<T> rows, java.util.function.Function<T, String> id) {
        List<T> mine = new ArrayList<>(Json.read(file, type, List.of()));
        java.util.Set<String> have = new java.util.HashSet<>();
        mine.forEach(x -> have.add(id.apply(x)));
        int added = 0;
        for (T row : rows) {
            if (have.add(id.apply(row))) {
                mine.add(row);
                added++;
            }
        }
        Json.write(file, mine);
        return added;
    }

    interface Row<T> {
        T read(ResultSet r) throws SQLException;
    }

    private static <T> List<T> rows(Connection c, String sql, Row<T> row) throws SQLException {
        List<T> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            while (r.next()) out.add(row.read(r));
        }
        return out;
    }

    private static Instant instant(ResultSet r, String column) throws SQLException {
        OffsetDateTime t = r.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static Instant latest(Instant a, Instant b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }

    private static Instant max(Instant a, Instant b) {
        return latest(a, b);
    }
}
