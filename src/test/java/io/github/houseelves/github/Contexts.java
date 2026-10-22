package io.github.houseelves.github;

import java.time.Instant;
import java.util.List;

import io.github.houseelves.github.github.Item;
import io.github.houseelves.github.github.ItemContext;

/** Test builders for {@link ItemContext}. */
public final class Contexts {

    private Contexts() {
    }

    public static ItemContext pr(String repo, int number, String authorAssociation, ItemContext.Comment... comments) {
        return new ItemContext(repo, number, Item.Kind.PR, "Bump Vert.x", "https://github.com/" + repo + "/pull/" + number,
                "someone", authorAssociation, "Updates Vert.x to 4.5.22", "main", "vertx-4.5.22", List.of(comments));
    }

    public static ItemContext issue(String repo, int number, String authorAssociation, ItemContext.Comment... comments) {
        return new ItemContext(repo, number, Item.Kind.ISSUE, "Dev UI blank behind a proxy",
                "https://github.com/" + repo + "/issues/" + number, "reporter", authorAssociation,
                "Dev UI is blank when served under /app", null, null, List.of(comments));
    }

    public static ItemContext.Comment comment(String association, String body) {
        return new ItemContext.Comment("x", association, body, Instant.parse("2026-10-05T00:00:00Z"), null);
    }
}
