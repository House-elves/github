package io.github.houseelves.github.board;

import java.time.Instant;
import java.util.List;

/**
 * Where an item is in its flow, whose move it is, and what to do next. The board groups rows by {@link #waiting}
 * and shows {@link #say} and {@link #next} on each, so the rules live in {@link FlowClassifier} only.
 *
 * @param role    mine (your PR), review (someone else's PR), issue (assigned to you), involved
 * @param steps   the flow's stages, for the row's stepper; empty when the item has no flow (involved)
 * @param step    the current stage, an index into {@code steps}
 * @param waiting whose move it is: {@link #YOU}, {@link #AGENT}, {@link #CI}, {@link #OTHERS}, {@link #DONE},
 *                {@link #WATCHING} or {@link #PARKED} (your draft PRs: on ice until you mark them ready)
 * @param since   when it started waiting there (best effort), for "for 3 days"
 * @param say     one sentence on where it is
 * @param next    the main action, or null when there is nothing for you to do
 * @param more    other actions that fit now, after {@code next}
 */
public record Flow(String role, List<String> steps, int step, String waiting, Instant since, String say, Next next,
        List<Next> more) {

    public static final String YOU = "you", AGENT = "agent", CI = "ci", OTHERS = "others", DONE = "done",
            WATCHING = "watching", PARKED = "parked";

    /**
     * An action the page knows how to run.
     *
     * @param id     open (the item's agent panel), review, investigate, fix, ci-why, ci-fix, ci-rerun, ci-reopen, ci-approve,
     *               conflicts, update-branch, read-review, address, dependabot-rebase, merge, merge-anyway, ready, close,
     *               whats-new, go (another item's page: {@code target})
     * @param label  the button text
     * @param target the other item's key, for go
     */
    public record Next(String id, String label, String target) {

        public Next(String id, String label) {
            this(id, label, null);
        }
    }

    Flow with(String newWaiting, Instant newSince, String newSay, Next newNext, List<Next> newMore) {
        return new Flow(role, steps, step, newWaiting, newSince, newSay, newNext, newMore);
    }

    Flow at(int newStep) {
        return new Flow(role, steps, newStep, waiting, since, say, next, more);
    }
}
