package io.github.houseelves.github.mvnpm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.houseelves.github.Items;

class MvnpmElfTest {

    final ObjectMapper mapper = new ObjectMapper();

    MvnpmElf elf(Path dir) {
        return new MvnpmElf(dir.resolve("config"), dir, mapper);
    }

    @Test
    void readsReposAndState() {
        MvnpmElf elf = elf(Path.of("src/test/resources/mvnpm"));
        assertTrue(elf.installed());
        assertEquals(List.of("quarkusio/quarkus", "smallrye/smallrye-open-api"), elf.repos());

        var check = MvnpmElf.check(elf.state(), "smallrye/smallrye-open-api", 2696).orElseThrow();
        assertEquals("SAFE", check.verdict());
        assertTrue(check.approved());
        assertEquals(Instant.parse("2026-10-06T00:30:00Z"), check.at());
    }

    @Test
    void legacyBareNumberKeysAreQuarkusOnly() {
        var state = elf(Path.of("src/test/resources/mvnpm")).state();
        assertEquals("NOT_SAFE", MvnpmElf.check(state, "quarkusio/quarkus", 50120).orElseThrow().verdict());
        assertTrue(MvnpmElf.check(state, "smallrye/smallrye-open-api", 50120).isEmpty());
    }

    @Test
    void notInstalledMeansNoRepos(@TempDir Path dir) {
        MvnpmElf elf = elf(dir);
        assertFalse(elf.installed());
        assertTrue(elf.repos().isEmpty());
        assertTrue(elf.state().isEmpty());
    }

    @Test
    void reposDefaultsToQuarkusAndFallsBackToRepo(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("config"), "GITHUB_USER=x\n");
        assertEquals(List.of("quarkusio/quarkus"), elf(dir).repos());
        Files.writeString(dir.resolve("config"), "REPO=smallrye/smallrye-open-api\n");
        assertEquals(List.of("smallrye/smallrye-open-api"), elf(dir).repos());
    }

    @Test
    void recognisesMvnpmBumpsLikeTheElf() {
        assertTrue(MvnpmElf.isMvnpmBump(Items.mvnpmBump("quarkusio/quarkus", 1, "s")));
        assertTrue(MvnpmElf.isMvnpmBump(Items.pr("quarkusio/quarkus", 2, "dependabot", "Bump org.mvnpm:x", "branch", "s")));
        assertFalse(MvnpmElf.isMvnpmBump(Items.pr("quarkusio/quarkus", 3, "dependabot", "Bump io.vertx:x", "dependabot/io.vertx", "s")));
        assertFalse(MvnpmElf.isMvnpmBump(Items.pr("quarkusio/quarkus", 4, "phillip-kruger", "Bump org.mvnpm:x", "b", "s")));
    }
}
