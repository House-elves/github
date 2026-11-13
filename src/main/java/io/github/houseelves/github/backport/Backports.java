package io.github.houseelves.github.backport;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.houseelves.github.elf.Json;
import io.github.houseelves.github.elf.Log;
import io.github.houseelves.github.github.GitHubWriter;
import io.github.houseelves.github.github.OneAtATime;

/**
 * Backport labels on merged PRs, as Quarkus does it: {@code triage/backport-3.40} asks for a backport to the 3.40
 * branch, and {@code triage/backport} to the newest release branch. A repo takes part when it has such labels.
 *
 * <p>
 * Before a label goes on, the PR is cherry-picked onto its branch, without a checkout: {@code git merge-tree} merges
 * the PR's change (from where it left its base to its head) into the branch, in a bare, shallow, blobless copy of the
 * repo in {@code git/}, which fetches only what that merge reads. A conflict means it can't be backported as is,
 * and no label is added.
 */
public class Backports {

    private static final Log LOG = Log.of(Backports.class);

    /** triage/backport, triage/backport-3.40, backport-2.x? ...: the version, when there is one, is the branch. */
    public static final Pattern LABEL = Pattern.compile("(?:[A-Za-z0-9 _.-]{1,40}/)?backport(?:-([0-9][A-Za-z0-9.]{0,20}))?\\??",
            Pattern.CASE_INSENSITIVE);
    /** A release branch: 3.40, 4.0. */
    static final Pattern RELEASE = Pattern.compile("(\\d+)\\.(\\d+)");
    /** How long a merged PR is offered for backporting. */
    public static final int DAYS = 7;

    /** GitHub's REST API, read with the account's token. Tests fake it. */
    public interface Rest {
        /** The JSON at this path, or null when GitHub says 404. */
        JsonNode get(String path);
    }

    /** The cherry-pick, tried. Tests fake it. */
    public interface Git {
        /** The files that conflict when the PR's change ({@code from} to {@code head}) goes onto {@code branch}. */
        List<String> conflicts(String repo, int number, String from, String head, String branch);
    }

    /** A label to add, and the branch it backports to. */
    public record Label(String name, String branch) {
    }

    /** A merged PR you can ask to backport: its labels now, and the backport labels its repo has. */
    public record Merged(String key, String repo, int number, String title, String url, String author, Instant mergedAt,
            List<String> labels, List<Label> backport, String as) {
    }

    /** What the cherry-pick said: clean, or the files that conflict. {@code labelled}: it has the label already. */
    public record Check(String itemKey, String label, String branch, boolean clean, List<String> conflicts, boolean labelled) {
    }

    private final Function<String, Rest> rest;
    private final Git git;

    /** {@code rest}: GitHub as the account with this token. */
    public Backports(Function<String, Rest> rest, Git git) {
        this.rest = rest;
        this.git = git;
    }

    /** The real thing: api.github.com, and git in {@code gitDir}. */
    public static Backports real(Path gitDir) {
        return new Backports(Backports::http, new CliGit(gitDir));
    }

    // ---- the board's list ----

    /**
     * Your PRs and the ones you're involved in, merged in the last {@link #DAYS} days, in repos with backport labels.
     * Newest merge first.
     */
    public List<Merged> recent(String login, String token, Instant now) {
        Rest gh = rest.apply(token);
        String since = LocalDate.ofInstant(now, ZoneOffset.UTC).minusDays(DAYS).toString();
        String q = "is:pr is:merged merged:>=" + since + " involves:" + login + " archived:false";
        JsonNode found = gh.get("/search/issues?per_page=100&sort=updated&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8));
        Map<String, List<Label>> labelsOf = new HashMap<>();
        List<Merged> out = new ArrayList<>();
        for (JsonNode i : found == null ? Json.MAPPER.createArrayNode() : found.path("items")) {
            String repo = i.path("repository_url").asText("").replaceFirst("^https://api\\.github\\.com/repos/", "");
            if (!GitHubWriter.REPO_NAME.matcher(repo).matches()) continue;
            List<Label> backport = labelsOf.computeIfAbsent(repo, r -> {
                try {
                    return labels(gh, r);
                } catch (RuntimeException e) {
                    LOG.warnf("Reading the labels of %s failed: %s", r, e.getMessage());
                    return List.of();
                }
            });
            if (backport.isEmpty()) continue;
            int number = i.path("number").asInt();
            List<String> has = new ArrayList<>();
            i.path("labels").forEach(l -> has.add(l.path("name").asText()));
            String merged = i.path("pull_request").path("merged_at").asText(null);
            out.add(new Merged(repo + "#" + number, repo, number, i.path("title").asText(), i.path("html_url").asText(),
                    i.path("user").path("login").asText(), merged == null ? null : Instant.parse(merged), has, backport, login));
        }
        out.sort(Comparator.comparing(Merged::mergedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /** The repo's backport labels, each with its branch (labels whose branch isn't there are left out). */
    List<Label> labels(Rest gh, String repo) {
        List<String> names = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            JsonNode p = gh.get("/repos/" + repo + "/labels?per_page=100&page=" + page);
            if (p == null || !p.isArray() || p.isEmpty()) break;
            p.forEach(l -> {
                String name = l.path("name").asText();
                if (LABEL.matcher(name).matches()) names.add(name);
            });
            if (p.size() < 100) break;
        }
        if (names.isEmpty()) return List.of();
        List<String> branches = releaseBranches(gh, repo);
        List<Label> out = new ArrayList<>();
        for (String name : names) {
            String branch = branchOf(name, branches);
            if (branch != null) out.add(new Label(name, branch));
        }
        // Newest branch first.
        out.sort(Comparator.comparing((Label l) -> version(l.branch()), Comparator.reverseOrder()));
        return out;
    }

    /** The repo's release branches (X.Y). */
    static List<String> releaseBranches(Rest gh, String repo) {
        JsonNode refs = gh.get("/repos/" + repo + "/git/matching-refs/heads/");
        List<String> out = new ArrayList<>();
        if (refs != null) {
            refs.forEach(r -> {
                String name = r.path("ref").asText().replaceFirst("^refs/heads/", "");
                if (RELEASE.matcher(name).matches()) out.add(name);
            });
        }
        return out;
    }

    /** The branch a label backports to: its version's, or the newest release branch for a plain one. */
    static String branchOf(String label, List<String> releaseBranches) {
        Matcher m = LABEL.matcher(label);
        if (!m.matches()) return null;
        String v = m.group(1);
        if (v != null) return releaseBranches.contains(v) ? v : null;
        return releaseBranches.stream().max(Comparator.comparing(Backports::version)).orElse(null);
    }

    private static Long version(String branch) {
        Matcher m = RELEASE.matcher(branch);
        return m.matches() ? Long.parseLong(m.group(1)) * 100_000 + Long.parseLong(m.group(2)) : -1L;
    }

    // ---- the check, and the label ----

    /** Does the merged PR cherry-pick cleanly onto the label's branch? */
    public Check check(String token, String itemKey, String label) {
        String repo = itemKey.substring(0, itemKey.indexOf('#'));
        int number = Integer.parseInt(itemKey.substring(itemKey.indexOf('#') + 1));
        if (!LABEL.matcher(label).matches()) throw new IllegalArgumentException("Not a backport label: " + label);
        Rest gh = rest.apply(token);
        JsonNode pr = gh.get("/repos/" + repo + "/pulls/" + number);
        if (pr == null) throw new IllegalStateException("There's no PR " + itemKey + ".");
        if (!pr.path("merged").asBoolean()) throw new IllegalStateException(itemKey + " isn't merged, so there's nothing to backport yet.");
        Label l = labels(gh, repo).stream().filter(x -> x.name().equals(label)).findFirst()
                .orElseThrow(() -> new IllegalStateException(repo + " has no label " + label + " with a branch to backport to."));
        boolean labelled = false;
        for (JsonNode x : pr.path("labels")) labelled |= x.path("name").asText().equals(label);
        String base = pr.path("base").path("sha").asText(), head = pr.path("head").path("sha").asText();
        JsonNode cmp = gh.get("/repos/" + repo + "/compare/" + base + "..." + head);
        String from = cmp == null ? null : cmp.path("merge_base_commit").path("sha").asText(null);
        if (from == null || !GitHubWriter.SHA.matcher(from).matches() || !GitHubWriter.SHA.matcher(head).matches()) {
            throw new IllegalStateException("GitHub didn't say where " + itemKey + " left its base, so it can't be tried.");
        }
        List<String> conflicts = git.conflicts(repo, number, from, head, l.branch());
        return new Check(itemKey, label, l.branch(), conflicts.isEmpty(), conflicts, labelled);
    }

    // ---- the real GitHub and git ----

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();

    static Rest http(String token) {
        return path -> {
            try {
                var r = OneAtATime.send(HTTP, HttpRequest.newBuilder(URI.create("https://api.github.com" + path))
                        .header("Authorization", "Bearer " + token).header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "github-elf")
                        .timeout(Duration.ofSeconds(45)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 404) return null;
                if (r.statusCode() != 200) throw new IllegalStateException("GitHub said HTTP " + r.statusCode() + " to " + path.replaceAll("\\?.*", ""));
                return Json.MAPPER.readTree(r.body());
            } catch (IOException e) {
                throw new IllegalStateException("Couldn't reach GitHub: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted");
            }
        };
    }

    /**
     * git, in a bare copy of each repo ({@code dir}/owner_repo.git) that fetches commits one level deep and blobs
     * only when the merge reads them. The token goes to git in its environment, never on its command line.
     */
    public static class CliGit implements Git {
        private final Path dir;
        private final java.util.function.Supplier<String> token;

        public CliGit(Path dir) {
            this(dir, () -> null);
        }

        public CliGit(Path dir, java.util.function.Supplier<String> token) {
            this.dir = dir;
            this.token = token;
        }

        CliGit withToken(String t) {
            return new CliGit(dir, () -> t);
        }

        @Override
        public List<String> conflicts(String repo, int number, String from, String head, String branch) {
            if (!GitHubWriter.REPO_NAME.matcher(repo).matches() || !RELEASE.matcher(branch).matches()) {
                throw new IllegalArgumentException("Refused: " + repo + " " + branch);
            }
            Path g = dir.resolve(repo.replace('/', '_') + ".git");
            if (!Files.isDirectory(g)) {
                run(null, "init", "-q", "--bare", g.toString());
                run(g, "remote", "add", "origin", "https://github.com/" + repo + ".git");
                run(g, "config", "remote.origin.promisor", "true");
                run(g, "config", "remote.origin.partialclonefilter", "blob:none");
            }
            String pr = "refs/backport/pr-" + number, onto = "refs/backport/" + branch;
            run(g, "fetch", "-q", "--filter=blob:none", "--depth=1", "origin", from,
                    "+refs/pull/" + number + "/head:" + pr, "+refs/heads/" + branch + ":" + onto);
            Result r = exec(g, "merge-tree", "--write-tree", "--name-only", "--no-messages", "--merge-base=" + from, onto, head);
            if (r.status == 0) return List.of();
            if (r.status != 1) throw new IllegalStateException("git couldn't try the cherry-pick: " + r.out.strip());
            List<String> lines = new ArrayList<>(r.out.lines().filter(s -> !s.isBlank()).toList());
            if (!lines.isEmpty()) lines.removeFirst(); // the tree
            return List.copyOf(new java.util.LinkedHashSet<>(lines));
        }

        private void run(Path g, String... args) {
            Result r = exec(g, args);
            if (r.status != 0) throw new IllegalStateException("git " + args[0] + " failed: " + r.out.strip());
        }

        record Result(int status, String out) {
        }

        private Result exec(Path g, String... args) {
            List<String> cmd = new ArrayList<>(List.of("git"));
            if (g != null) cmd.addAll(List.of("-C", g.toString()));
            cmd.addAll(List.of(args));
            try {
                Files.createDirectories(dir);
                ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
                Map<String, String> env = pb.environment();
                env.put("GIT_TERMINAL_PROMPT", "0");
                String t = token.get();
                if (t != null && !t.isBlank()) {
                    env.put("GIT_CONFIG_COUNT", "1");
                    env.put("GIT_CONFIG_KEY_0", "http.https://github.com/.extraheader");
                    env.put("GIT_CONFIG_VALUE_0", "Authorization: Basic "
                            + Base64.getEncoder().encodeToString(("x-access-token:" + t).getBytes(StandardCharsets.UTF_8)));
                }
                Process p = pb.start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!p.waitFor(5, TimeUnit.MINUTES)) {
                    p.destroyForcibly();
                    return new Result(124, "timed out");
                }
                return new Result(p.exitValue(), out);
            } catch (IOException e) {
                return new Result(127, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(130, "interrupted");
            }
        }
    }

    /** The real git with this account's token, for a private repo. */
    public Backports withToken(String token) {
        return git instanceof CliGit c ? new Backports(rest, c.withToken(token)) : this;
    }

    /**
     * The merged PRs you said not to backport, in state/backport-skipped.json ({key: when}): they leave the Merged list
     * for good. Kept for a month, longer than a PR stays on the list.
     */
    public static Map<String, Instant> skipped(java.nio.file.Path stateDir) {
        Map<String, Instant> out = new LinkedHashMap<>();
        java.nio.file.Path f = stateDir.resolve("backport-skipped.json");
        try {
            if (java.nio.file.Files.exists(f)) {
                JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(f.toFile());
                Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(30));
                n.fields().forEachRemaining(e -> {
                    try {
                        Instant at = Instant.parse(e.getValue().asText());
                        if (at.isAfter(cutoff)) out.put(e.getKey(), at);
                    } catch (Exception ignored) {
                        // A broken entry is dropped.
                    }
                });
            }
        } catch (Exception e) {
            LOG.warn("Couldn't read " + f + ": " + e.getMessage());
        }
        return out;
    }

    /** Not to backport: off the Merged list from now on. */
    public static void skip(java.nio.file.Path stateDir, String itemKey) throws java.io.IOException {
        Map<String, Instant> all = skipped(stateDir);
        all.put(itemKey, Instant.now());
        Map<String, String> out = new LinkedHashMap<>();
        all.forEach((k, v) -> out.put(k, v.toString()));
        io.github.houseelves.github.elf.Json.write(stateDir.resolve("backport-skipped.json"), out);
    }

    /** board/merged.json: what the board's Merged list shows. */
    public static Map<String, Object> view(Instant at, List<Merged> merged) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fetchedAt", at);
        out.put("days", DAYS);
        out.put("items", merged);
        return out;
    }
}
