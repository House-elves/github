package io.github.houseelves.github.backport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class BackportsTest {

    final ObjectMapper mapper = new ObjectMapper();
    final List<String> tried = new ArrayList<>();
    List<String> conflicts = List.of();

    static final String BASE = "a".repeat(40), HEAD = "b".repeat(40), FROM = "c".repeat(40);

    Map<String, String> github = Map.of(
            "/repos/quarkusio/quarkus/labels?per_page=100&page=1",
            "[{\"name\":\"area/core\"},{\"name\":\"triage/backport\"},{\"name\":\"triage/backport-3.40\"},"
                    + "{\"name\":\"triage/backport-3.20\"},{\"name\":\"triage/backport-2.16\"}]",
            "/repos/quarkusio/quarkus-chappie/labels?per_page=100&page=1", "[{\"name\":\"bug\"}]",
            "/repos/quarkusio/quarkus/git/matching-refs/heads/",
            "[{\"ref\":\"refs/heads/main\"},{\"ref\":\"refs/heads/3.20\"},{\"ref\":\"refs/heads/3.40\"},"
                    + "{\"ref\":\"refs/heads/4.0\"},{\"ref\":\"refs/heads/3.40.1-emergency\"}]",
            "/repos/quarkusio/quarkus/pulls/58273",
            "{\"merged\":true,\"labels\":[{\"name\":\"triage/backport-3.20\"}],\"base\":{\"sha\":\"" + BASE
                    + "\"},\"head\":{\"sha\":\"" + HEAD + "\"}}",
            "/repos/quarkusio/quarkus/pulls/1", "{\"merged\":false,\"labels\":[]}",
            "/repos/quarkusio/quarkus/compare/" + BASE + "..." + HEAD, "{\"merge_base_commit\":{\"sha\":\"" + FROM + "\"}}");

    Backports backports() {
        return new Backports(token -> path -> {
            if (path.startsWith("/search/issues")) {
                return read("{\"items\":["
                        + "{\"number\":58273,\"title\":\"Bump\",\"html_url\":\"u\",\"user\":{\"login\":\"me\"},"
                        + "\"repository_url\":\"https://api.github.com/repos/quarkusio/quarkus\",\"labels\":[],"
                        + "\"pull_request\":{\"merged_at\":\"2026-11-11T10:00:00Z\"}},"
                        + "{\"number\":9,\"title\":\"Chappie\",\"html_url\":\"u\",\"user\":{\"login\":\"me\"},"
                        + "\"repository_url\":\"https://api.github.com/repos/quarkusio/quarkus-chappie\",\"labels\":[],"
                        + "\"pull_request\":{\"merged_at\":\"2026-11-12T10:00:00Z\"}}]}");
            }
            String body = github.get(path);
            return body == null ? null : read(body);
        }, (repo, number, from, head, branch) -> {
            tried.add(repo + "#" + number + " " + from.charAt(0) + ".." + head.charAt(0) + " onto " + branch);
            return conflicts;
        });
    }

    JsonNode read(String s) {
        try {
            return mapper.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void labelsAreQuarkusStyle() {
        assertTrue(Backports.LABEL.matcher("triage/backport").matches());
        assertTrue(Backports.LABEL.matcher("triage/backport-3.40").matches());
        assertTrue(Backports.LABEL.matcher("triage/backport?").matches());
        assertFalse(Backports.LABEL.matcher("area/core").matches());
        assertFalse(Backports.LABEL.matcher("triage/backport-3.40 ; rm").matches());
    }

    @Test
    void aPlainLabelIsTheNewestReleaseBranch() {
        List<String> branches = List.of("3.20", "3.40", "4.0");
        assertEquals("4.0", Backports.branchOf("triage/backport", branches));
        assertEquals("3.40", Backports.branchOf("triage/backport-3.40", branches));
        assertNull(Backports.branchOf("triage/backport-2.16", branches));
    }

    @Test
    void recentOnlyInReposWithBackportLabels() {
        var merged = backports().recent("me", "t", Instant.parse("2026-11-12T12:00:00Z"));
        assertEquals(1, merged.size());
        var m = merged.getFirst();
        assertEquals("quarkusio/quarkus#58273", m.key());
        // 2.16 has no branch: not offered. Newest branch first.
        assertEquals(List.of("triage/backport:4.0", "triage/backport-3.40:3.40", "triage/backport-3.20:3.20"),
                m.backport().stream().map(l -> l.name() + ":" + l.branch()).toList());
    }

    @Test
    void theCheckCherryPicksThePrsChangeOntoTheBranch() {
        var c = backports().check("t", "quarkusio/quarkus#58273", "triage/backport-3.40");
        assertTrue(c.clean());
        assertFalse(c.labelled());
        assertEquals("3.40", c.branch());
        assertEquals(List.of("quarkusio/quarkus#58273 c..b onto 3.40"), tried);

        conflicts = List.of("bom/application/pom.xml");
        c = backports().check("t", "quarkusio/quarkus#58273", "triage/backport-3.20");
        assertFalse(c.clean());
        assertTrue(c.labelled());
        assertEquals(List.of("bom/application/pom.xml"), c.conflicts());
    }

    @Test
    void onlyMergedPrsAndRealLabels() {
        assertThrows(IllegalStateException.class, () -> backports().check("t", "quarkusio/quarkus#1", "triage/backport"));
        assertThrows(IllegalStateException.class, () -> backports().check("t", "quarkusio/quarkus#58273", "triage/backport-2.16"));
        assertThrows(IllegalArgumentException.class, () -> backports().check("t", "quarkusio/quarkus#58273", "area/core"));
        assertTrue(tried.isEmpty());
    }

    @Test
    void notToBackportStaysOffTheList(@org.junit.jupiter.api.io.TempDir java.nio.file.Path state) throws Exception {
        assertTrue(Backports.skipped(state).isEmpty());
        Backports.skip(state, "quarkusio/quarkus#58273");
        Backports.skip(state, "quarkusio/quarkus#58290");
        assertEquals(java.util.Set.of("quarkusio/quarkus#58273", "quarkusio/quarkus#58290"), Backports.skipped(state).keySet());
        // An old one is forgotten: the PR left the list long ago.
        java.nio.file.Files.writeString(state.resolve("backport-skipped.json"), "{\"a/b#1\":\"2020-01-01T00:00:00Z\"}");
        assertTrue(Backports.skipped(state).isEmpty());
    }
}
