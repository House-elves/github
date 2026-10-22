package io.github.houseelves.github.thread;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Where a review's inline comments can go: the new-side lines of the PR's diff. */
class ReviewLinesTest {

    static final String DIFF = """
            diff --git a/src/A.java b/src/A.java
            index 1111111..2222222 100644
            --- a/src/A.java
            +++ b/src/A.java
            @@ -10,4 +10,5 @@ class A {
                 int a;
            -    int b;
            +    int c;
            +    int d;
                 int e;
            @@ -40 +41 @@
            -x
            +y
            diff --git a/old/B.java b/new/B.java
            similarity index 100%
            rename from old/B.java
            rename to new/B.java
            diff --git a/gone.sh b/gone.sh
            deleted file mode 100755
            --- a/gone.sh
            +++ /dev/null
            @@ -1,2 +0,0 @@
            -#!/bin/sh
            -+++ not a header
            diff --git a/new.txt b/new.txt
            new file mode 100644
            --- /dev/null
            +++ b/new.txt
            @@ -0,0 +1,2 @@
            ++++ a line that starts with pluses
            +two
            \\ No newline at end of file
            """;

    @Test
    void addedAndContextLinesOnTheNewSide() {
        Map<String, Set<Integer>> lines = Poster.rightSideLines(DIFF);
        assertEquals(Set.of(10, 11, 12, 13, 41), lines.get("src/A.java"));
        assertEquals(Set.of(1, 2), lines.get("new.txt"));
    }

    @Test
    void deletedAndPureRenamesHaveNoLines() {
        Map<String, Set<Integer>> lines = Poster.rightSideLines(DIFF);
        assertNull(lines.get("gone.sh"));
        assertFalse(lines.containsKey("new/B.java"));
    }
}
