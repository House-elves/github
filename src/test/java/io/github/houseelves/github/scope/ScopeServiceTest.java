package io.github.houseelves.github.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopeServiceTest {

    @TempDir
    Path dir;

    ScopeService scopes;

    @BeforeEach
    void setUp() {
        scopes = new ScopeService(dir);
    }

    List<String> rows() {
        return scopes.rows().stream().map(w -> w.kind + ":" + w.name + ":" + w.mode).toList();
    }

    @Test
    void watchesOwnersAndReposNormalised() {
        scopes.watch("QuarkusIO");
        scopes.watch("https://github.com/mvnpm/mvnpm/");
        scopes.watch("quarkusio"); // again: no duplicate
        assertEquals(List.of("owner:quarkusio:include", "repo:mvnpm/mvnpm:include"), rows());
    }

    @Test
    void rejectsRubbishAndAlreadyCoveredRepos() {
        assertThrows(IllegalArgumentException.class, () -> scopes.watch("not a repo"));
        assertThrows(IllegalArgumentException.class, () -> scopes.watch("a/b/c"));
        scopes.watch("quarkusio");
        var e = assertThrows(IllegalArgumentException.class, () -> scopes.watch("quarkusio/quarkus"));
        assertTrue(e.getMessage().contains("already covered"));
    }

    @Test
    void excludeNeedsAWatchedOwnerAndWatchingLiftsIt() {
        assertThrows(IllegalArgumentException.class, () -> scopes.exclude("quarkusio/quarkus-quickstarts"));
        scopes.watch("quarkusio");
        scopes.exclude("quarkusio/quarkus-quickstarts");
        assertFalse(scopes.scope().covers("quarkusio/quarkus-quickstarts"));

        scopes.watch("quarkusio/quarkus-quickstarts");
        assertTrue(scopes.scope().covers("quarkusio/quarkus-quickstarts"));
        assertEquals(List.of("owner:quarkusio:include"), rows());
    }

    @Test
    void removingAnOwnerDropsItsExclusions() {
        scopes.watchAll(List.of("quarkusio", "smallrye"));
        scopes.exclude("quarkusio/quarkus-quickstarts");
        scopes.exclude("smallrye/smallrye-config");
        long quarkusio = scopes.rows().stream().filter(w -> w.name.equals("quarkusio")).findFirst().orElseThrow().id;

        scopes.remove(quarkusio);

        assertEquals(List.of("owner:smallrye:include", "repo:smallrye/smallrye-config:exclude"), rows());
    }

    @Test
    void theScopeIsKeptInStateScopeJson() {
        scopes.watch("quarkusio");
        scopes.exclude("quarkusio/quarkus-quickstarts");
        ScopeService again = new ScopeService(dir);
        assertFalse(again.scope().covers("quarkusio/quarkus-quickstarts"));
        assertTrue(again.scope().covers("quarkusio/quarkus"));
    }
}
