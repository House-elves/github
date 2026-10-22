package io.github.houseelves.github.thread;

import java.time.Instant;

/**
 * Something postable the agent produced, held until you post or discard it.
 * {@code payload} is the JSON of {@link io.github.houseelves.github.agent.AgentOutput.DraftOut}, or for a
 * push, of {@link PushOut}, or for a new PR, of {@link PrOut}.
 */
public class Draft {

    public static final String REVIEW = "review", COMMENT = "comment", PUSH = "push", ISSUE = "issue", PR = "pr";
    public static final String PENDING = "draft", POSTED = "posted", DISCARDED = "discarded";

    public Long id;

    public Long threadId;

    public Long jobId;

    public String kind;

    public String payload;

    public String state = PENDING;

    public String postedUrl;

    public Instant postedAt;

    public Instant created = Instant.now();

    /** The preview you saw, which {@link Poster#post} checks: the desk kept it in memory, the elf runs anew each time. */
    public Confirm confirm;

    /** A push or PR that {@link Poster#post} handed to the runner, which {@link Poster#postDone} finishes. */
    public Pushing pushing;

    /** A push's preview offered to ask these reviewers again once it's pushed (you pushed since their review). */
    public java.util.List<String> askAgain;

    /** A preview's one-time confirmation, for the draft as it was then. */
    public record Confirm(String token, String payload, Instant expires) {
    }

    /**
     * What was handed to the runner to push, and when.
     *
     * @param asDraft a new PR opens as a draft (true) or ready for review
     */
    /** @param askAgain reviewers to ask to review again once the runner pushed (empty: nobody) */
    public record Pushing(String from, String to, Instant at, boolean asDraft, java.util.List<String> askAgain) {

        public Pushing(String from, String to, Instant at, boolean asDraft) {
            this(from, to, at, asDraft, java.util.List.of());
        }

        public Pushing {
            askAgain = askAgain == null ? java.util.List.of() : java.util.List.copyOf(askAgain);
        }
    }
}
