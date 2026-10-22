package io.github.houseelves.github.scope;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class ScopeTest {

    final Scope scope = new Scope(Set.of("QuarkusIO"), Set.of("mvnpm/mvnpm"), Set.of("quarkusio/quarkus-quickstarts"));

    @Test
    void ownerCoversItsReposCaseInsensitively() {
        assertTrue(scope.covers("quarkusio/quarkus"));
        assertTrue(scope.covers("QuarkusIO/Quarkus"));
    }

    @Test
    void exclusionWins() {
        assertFalse(scope.covers("quarkusio/quarkus-quickstarts"));
    }

    @Test
    void singleRepoIsCoveredButNotItsSiblings() {
        assertTrue(scope.covers("mvnpm/mvnpm"));
        assertFalse(scope.covers("mvnpm/locker"));
    }

    @Test
    void ownerPrefixIsNotEnough() {
        assertFalse(scope.covers("quarkusio-extra/quarkus"));
    }
}
