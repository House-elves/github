package io.github.houseelves.github.merge;

import java.time.Duration;
import java.time.Instant;

import io.github.houseelves.github.github.GitHub;
import io.github.houseelves.github.github.Item;

/** What merge on green does next for one PR, given its state on GitHub. Pure, so the rules are easy to test. */
public record MergeDecision(Step step, String reason) {

    public enum Step {
        /** Not green yet: check again next round. */
        WAIT,
        /** Green and mergeable: merge now, pinned to the watched head. */
        MERGE,
        /** Stop: something needs you (CI failed, conflicts, new commits, too long). */
        STOP,
        /** Stop quietly: someone already merged or closed it. */
        DONE
    }

    public static final Duration GIVE_UP_AFTER = Duration.ofHours(24);

    static MergeDecision decide(String watchedSha, Instant since, Instant now, GitHub.PullState pr) {
        return decide(watchedSha, since, now, pr, true);
    }

    /** @param requireApproval false for "Merge without approval", where you chose to merge without an approval */
    static MergeDecision decide(String watchedSha, Instant since, Instant now, GitHub.PullState pr,
            boolean requireApproval) {
        if (pr.state().equals("MERGED")) return new MergeDecision(Step.DONE, "It was merged on GitHub.");
        if (pr.state().equals("CLOSED")) return new MergeDecision(Step.DONE, "It was closed without merging.");
        if (!pr.headSha().equals(watchedSha)) {
            return new MergeDecision(Step.STOP, "New commits were pushed after you asked, so it wasn't merged. "
                    + "Look at them, then merge again.");
        }
        if (pr.draft()) return new MergeDecision(Step.STOP, "It's a draft PR. Mark it ready, then try again.");
        if (requireApproval && !pr.approved()) {
            return new MergeDecision(Step.STOP, "It's no longer approved (an approval was withdrawn or changes were "
                    + "requested), so it wasn't merged.");
        }
        if ("CONFLICTING".equals(pr.mergeable()) || "DIRTY".equals(pr.mergeState())) {
            return new MergeDecision(Step.STOP, "It has merge conflicts.");
        }
        if ("FAILURE".equals(pr.ci()) || "ERROR".equals(pr.ci())) {
            return new MergeDecision(Step.STOP, "CI failed, so it wasn't merged.");
        }
        if (!Duration.between(since, now).minus(GIVE_UP_AFTER).isNegative()) {
            return new MergeDecision(Step.STOP, "It still couldn't be merged after 24 hours, so the desk gave up.");
        }
        // Green means every check passed. A repo with no checks at all counts as green only when GitHub
        // itself says the merge is clean.
        // Green means every check reported passed AND GitHub says every required check passed (CLEAN): a PR
        // whose real CI hasn't started yet can look green with only a few early checks.
        boolean green = ("SUCCESS".equals(pr.ci()) || pr.ci() == null) && Item.requiredChecksPassed(pr.mergeState());
        if (green && "MERGEABLE".equals(pr.mergeable())) return new MergeDecision(Step.MERGE, null);
        return new MergeDecision(Step.WAIT, null);
    }
}
