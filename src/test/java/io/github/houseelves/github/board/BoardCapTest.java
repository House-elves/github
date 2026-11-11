package io.github.houseelves.github.board;

import static io.github.houseelves.github.Items.issue;
import static io.github.houseelves.github.Items.pr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.houseelves.github.github.Item;

class BoardCapTest {

    static final String ME = "phillip-kruger";
    static final String FIX_IT = "Investigated.";

    static Board board(List<Board.Lane> lanes) {
        Map<String, Flow> flows = new LinkedHashMap<>();
        for (Board.Lane l : lanes) {
            for (Item i : l.items()) {
                flows.put(i.key(), new Flow("issue", List.of(), 1, Flow.YOU, null, FIX_IT, new Flow.Next("fix", "Fix it"),
                        List.of()));
            }
        }
        return new Board(ME, Instant.EPOCH, 5, false, false, lanes, List.of(), List.of(), Map.of(), false, false, Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), flows, Map.of(), Map.of());
    }

    static Item writable(Item i, boolean canWrite) {
        return new Item(i.key(), i.repo(), i.number(), i.kind(), i.title(), i.url(), i.author(), i.assignees(), i.createdAt(),
                i.updatedAt(), i.comments(), i.draft(), i.headRef(), i.headSha(), i.ci(), i.reviewDecision(), i.mergeable(),
                i.mergeState(), i.approved(), canWrite, null);
    }

    static String say(Board b, Item i) {
        return b.flows().get(i.key()).say();
    }

    @Test
    void anIssueWaitsOnTheCapOnlyInAnotherProjectAtTheCap() {
        Item a = pr("quarkusio/quarkus", 1, ME), b = pr("quarkusio/quarkus", 2, ME), theirs = pr("quarkusio/quarkus", 3, "someone");
        Item fix = writable(issue("quarkusio/quarkus", 10, "reporter"), false);
        Item other = writable(issue("smallrye/smallrye-config", 11, "reporter"), false);
        // The same PR in two lanes counts once.
        Board board = board(List.of(new Board.Lane("mine", "Mine", List.of(a, b, theirs)),
                new Board.Lane("ci", "CI", List.of(a)), new Board.Lane("issues", "Issues", List.of(fix, other))));

        assertEquals(FIX_IT, say(board.capped(3), fix)); // 2 of yours: below the cap
        assertEquals("Waits on your cap: at least 2 open PRs in quarkusio/quarkus (at most 2)", say(board.capped(2), fix));
        assertEquals(FIX_IT, say(board.capped(2), other)); // none of yours there
        assertSame(board, board.capped(0)); // no cap

        // A repo you can write to: no cap.
        Item own = writable(issue("quarkusio/quarkus", 12, "reporter"), true);
        Board maintained = board(List.of(new Board.Lane("mine", "Mine", List.of(a, b)), new Board.Lane("issues", "Issues",
                List.of(own))));
        assertEquals(FIX_IT, say(maintained.capped(1), own));
    }
}
