package io.github.houseelves.github.github;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * What an agent needs to know about an issue or PR before it starts: the
 * text people wrote on it, with who wrote it. Text from people outside the
 * repo (see {@link #TRUSTED}) is untrusted input for the agent.
 *
 * @param baseRef PR only: the branch it merges into
 * @param headRef PR only
 */
public record ItemContext(
        String repo,
        int number,
        Item.Kind kind,
        String title,
        String url,
        String author,
        String authorAssociation,
        String body,
        String baseRef,
        String headRef,
        List<Comment> comments) {

    /** GitHub author associations whose text needs no triage: people with a role in the repo. */
    public static final Set<String> TRUSTED = Set.of("OWNER", "MEMBER", "COLLABORATOR");

    /**
     * A comment or review. {@code state} is set for reviews (APPROVED, CHANGES_REQUESTED, COMMENTED).
     */
    public record Comment(String author, String authorAssociation, String body, Instant createdAt, String state) {
    }

    public String key() {
        return Item.key(repo, number);
    }

    /** All the text written by people outside the repo, for the runner's safety triage. Empty when there is none. */
    public String untrustedText() {
        StringBuilder sb = new StringBuilder();
        if (!TRUSTED.contains(authorAssociation)) {
            sb.append(title).append("\n\n").append(nullToEmpty(body)).append("\n\n");
        }
        for (Comment c : comments) {
            if (!TRUSTED.contains(c.authorAssociation()) && c.body() != null && !c.body().isBlank()) {
                sb.append("---\n").append(c.body()).append("\n\n");
            }
        }
        return sb.toString().strip();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
