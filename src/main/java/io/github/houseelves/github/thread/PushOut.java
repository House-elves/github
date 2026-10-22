package io.github.houseelves.github.thread;

import java.util.List;

/**
 * Commits the agent made in a PR's worktree, to push to the PR's branch.
 *
 * @param headRepo where the PR's branch lives (often the contributor's fork)
 * @param from     the branch's head when the agent started; the push only goes through if it's still there
 * @param to       the worktree's head after the agent's commits
 * @param base     the PR's base branch (squashed pushes)
 * @param squashed {@code to} is the whole PR as one commit on top of {@code base}: the push replaces the branch
 *                 (force, with a lease on {@code from}) instead of adding to it
 * @param replaces squashed: how many commits the branch had on top of its base
 * @param patch    the change as a unified diff ({@code from} to {@code to}, or base to {@code to} when squashed), to
 *                 read before pushing; cut short when {@code patchCut}
 */
public record PushOut(String headRepo, String headRef, String from, String to, List<Commit> commits, String diffstat,
        String base, boolean squashed, int replaces, String patch, boolean patchCut) {

    public record Commit(String sha, String subject) {
    }

    /** The same push, to the branch worked out when you click Push. */
    public PushOut withTarget(String newHeadRepo, String newHeadRef) {
        return new PushOut(newHeadRepo, newHeadRef, from, to, commits, diffstat, base, squashed, replaces, patch, patchCut);
    }
}
