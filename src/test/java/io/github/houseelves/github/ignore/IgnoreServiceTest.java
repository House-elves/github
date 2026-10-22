package io.github.houseelves.github.ignore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.houseelves.github.thread.ThreadStore;

class IgnoreServiceTest {

    @TempDir
    Path dir;

    IgnoreService ignores;

    @BeforeEach
    void setUp() {
        ignores = new IgnoreService(dir, new ThreadStore(dir));
    }

    @Test
    void newActivityBringsItBackWithTheReasonAndIgnoringAgainResets() {
        ignores.ignore("quarkusio/quarkus#1", "me");
        assertTrue(ignores.isIgnored("quarkusio/quarkus#1"));
        assertTrue(ignores.ignoredSince().containsKey("quarkusio/quarkus#1"));

        ignores.bringBack("quarkusio/quarkus#1", "You were mentioned");
        assertFalse(ignores.isIgnored("quarkusio/quarkus#1"));
        assertFalse(ignores.ignoredSince().containsKey("quarkusio/quarkus#1"));
        assertEquals("You were mentioned", ignores.recentlyReturned().get("quarkusio/quarkus#1"));

        ignores.ignore("quarkusio/quarkus#1", "me");
        assertTrue(ignores.isIgnored("quarkusio/quarkus#1"));
        assertTrue(ignores.recentlyReturned().isEmpty());

        ignores.unignore("quarkusio/quarkus#1");
        assertFalse(ignores.isIgnored("quarkusio/quarkus#1"));
    }

    @Test
    void theyAreKeptInStateIgnoredJson() {
        ignores.ignore("quarkusio/quarkus#2", "me");
        assertTrue(dir.resolve("ignored.json").toFile().isFile());
        assertTrue(new IgnoreService(dir, new ThreadStore(dir)).isIgnored("quarkusio/quarkus#2"));
    }
}
