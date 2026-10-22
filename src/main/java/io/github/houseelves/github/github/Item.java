package io.github.houseelves.github.github;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * An open issue or PR as the board shows it. PR-only fields are null on issues.
 *
 * @param ci             rollup of the head commit's checks: SUCCESS, FAILURE, ERROR, PENDING, EXPECTED, or null when none ran
 * @param reviewDecision APPROVED, CHANGES_REQUESTED, REVIEW_REQUIRED, or null
 * @param mergeable      MERGEABLE, CONFLICTING or UNKNOWN
 * @param mergeState     PRs only: GitHub's mergeStateStatus. CLEAN (or HAS_HOOKS) means every required check passed
 *                       and nothing else blocks the merge; BLOCKED, BEHIND, DIRTY, UNSTABLE, DRAFT, UNKNOWN otherwise
 * @param approved       PRs only: approved by someone with write access and not blocked by a change request
 *                       (see {@link #isApproved})
 * @param canWrite       you have write access to the repo (which GitHub needs to re-run its workflows), or null when
 *                       not known
 * @param commitCount    PRs only: how many commits it has, or null when not known
 */
public record Item(
        String key,
        String repo,
        int number,
        Kind kind,
        String title,
        String url,
        String author,
        List<String> assignees,
        Instant createdAt,
        Instant updatedAt,
        int comments,
        Boolean draft,
        String headRef,
        String headSha,
        String ci,
        String reviewDecision,
        String mergeable,
        String mergeState,
        Boolean approved,
        Boolean canWrite,
        Integer commitCount) {

    public enum Kind { PR, ISSUE }

    public Item(String key, String repo, int number, Kind kind, String title, String url, String author,
            List<String> assignees, Instant createdAt, Instant updatedAt, int comments, Boolean draft, String headRef,
            String headSha, String ci, String reviewDecision, String mergeable, String mergeState, Boolean approved) {
        this(key, repo, number, kind, title, url, author, assignees, createdAt, updatedAt, comments, draft, headRef,
                headSha, ci, reviewDecision, mergeable, mergeState, approved, null, null);
    }

    public static String key(String repo, int number) {
        return repo + "#" + number;
    }

    public String owner() {
        return repo.substring(0, repo.indexOf('/'));
    }

    /**
     * Approved, for merge on green. Where the repo requires reviews, GitHub's decision says it. Elsewhere that
     * decision is empty, so: at least one reviewer with write access approved, and none of them (latest review
     * each) asks for changes.
     *
     * @param latestWriterReviews the latest APPROVED / CHANGES_REQUESTED review of each reviewer with write access
     */
    public static boolean isApproved(String reviewDecision, Collection<String> latestWriterReviews) {
        if (reviewDecision != null) return reviewDecision.equals("APPROVED");
        return latestWriterReviews.contains("APPROVED") && !latestWriterReviews.contains("CHANGES_REQUESTED");
    }

    /**
     * The same item with a different CI state, e.g. PENDING right after you re-ran failed jobs. GitHub works the
     * merge state out again after such a change, so it becomes UNKNOWN until the next fetch.
     */
    public Item withCi(String newCi) {
        return new Item(key, repo, number, kind, title, url, author, assignees, createdAt, updatedAt, comments, draft,
                headRef, headSha, newCi, reviewDecision, mergeable, isPr() ? "UNKNOWN" : mergeState, approved, canWrite, commitCount);
    }

    /** The same PR with the details search can't afford: merge state, and CI waiting for approval. */
    public Item withDetails(String newMergeState, boolean awaitingApproval) {
        return withDetails(newMergeState, awaitingApproval, false);
    }

    /**
     * @param passedOnRerun CI only fails on workflow runs that a newer run of the same workflow replaced, and that
     *                      one passed (GitHub's rollup still says FAILURE): it shows as SUCCESS
     */
    public Item withDetails(String newMergeState, boolean awaitingApproval, boolean passedOnRerun) {
        String newCi = awaitingApproval ? GitHub.AWAITING_APPROVAL
                : passedOnRerun && ("FAILURE".equals(ci) || "ERROR".equals(ci)) ? "SUCCESS" : ci;
        return new Item(key, repo, number, kind, title, url, author, assignees, createdAt, updatedAt, comments, draft,
                headRef, headSha, newCi, reviewDecision, mergeable, newMergeState, approved, canWrite, commitCount);
    }

    /**
     * The same PR at a new head, right after your push went out: CI runs again, and GitHub works the merge state
     * out again, so it's PENDING and UNKNOWN until the next fetch.
     */
    public Item withHead(String newHeadSha) {
        return new Item(key, repo, number, kind, title, url, author, assignees, createdAt, updatedAt, comments, draft,
                headRef, newHeadSha, ci, reviewDecision, mergeable, mergeState, approved, canWrite, commitCount).withCi("PENDING");
    }

    /** The same item with {@code login} added to its assignees (right after you assign yourself). */
    public Item withAssignee(String login) {
        if (assignees.stream().anyMatch(a -> a.equalsIgnoreCase(login))) return this;
        List<String> more = new java.util.ArrayList<>(assignees);
        more.add(login);
        return new Item(key, repo, number, kind, title, url, author, List.copyOf(more), createdAt, updatedAt, comments,
                draft, headRef, headSha, ci, reviewDecision, mergeable, mergeState, approved, canWrite, commitCount);
    }

    /** GitHub says every required check passed and nothing else blocks merging. */
    public static boolean requiredChecksPassed(String mergeState) {
        return "CLEAN".equals(mergeState) || "HAS_HOOKS".equals(mergeState);
    }

    public boolean isPr() {
        return kind == Kind.PR;
    }
}
