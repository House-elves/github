# The GitHub elf

Elf Desk's GitHub board, as a House Elves elf. It shows the open PRs and issues you're involved in, sorted by
whose move it is, puts an agent on any of them (review, investigate, fix, why is CI failing, address the review,
resolve conflicts), and writes to GitHub only when you click and confirm it.

It used to be part of the desk. Now the desk holds no GitHub token and no GitHub code: this elf owns the account,
the board's settings and state, and the GitHub tab.

## Adding it in Elf Desk

1. Log in to GitHub with `gh` as the account the board should work as: `gh auth login`.
2. In Elf Desk, click the **+** after the last tab, and add **GitHub** (House-elves/github).
3. Fill in its one setting, **GitHub account**. Its first refresh starts by itself.

The runner runs it with jbang (`GitHubElf.java`). It needs `gh`, `git` and `claude` on the machine.

## Settings

| Key              | What                                                                                   |
|------------------|----------------------------------------------------------------------------------------|
| `GITHUB_ACCOUNT` | The `gh` account the elf works as. Its token is `gh auth token -u <account>`, read fresh for each run, never written anywhere or logged. |

The board's own settings (what it watches, the repos you maintain, refresh minutes) are at the top of
its Settings pop-up, drawn by the elf; the account, version, updates and removing it follow, from the desk.
They're kept in `state/`, not in the config file.

## What it reads and writes

It reads, as your `gh` account: the open issues and PRs you're involved in, with their CI, reviews and merge
state, and the runner's reports on its agents.

Dependabot's org.mvnpm bumps aren't on the board: the mvnpm tab (the mvnpm-dependabot elf) handles them.

It writes its files in `~/.config/github/` (each written whole, then moved into place):

| Folder        | What                                                                                                    |
|---------------|---------------------------------------------------------------------------------------------------------|
| `board/`      | `board.json`, the board the tab shows; `cache.json`, what GitHub said at the last refresh               |
| `threads/`    | one file per item with a thread: its turns, the agent working on it, its draft                          |
| `state/`      | what the desk kept in its database: scope, settings, ignored items, merges, the log of GitHub writes, and each thread's record in `state/threads/` |
| `cache/`      | answers too big for one line (a PR's diff, its activity and reviews, a CI report), kept for a day        |
| `agents/`     | the runner's reports on the elf's agents, and the files they left (the runner writes this one)          |

On GitHub it writes only what you click and confirm in the desk's bar: reviews, comments, new issues and PRs
(from your fork), closing an item, assigning yourself, marking your PR ready, merges pinned to the head you saw
(merge on green waits for CI and checks again on each refresh), re-running and approving CI, and updating a branch.
An agent's commits are pushed by the runner, from its worktree, when you confirm
the push. Every write is in `state/actions.json`. The elf refuses any other write itself, with the same
allowlist the runner kept for the desk.

## Commands

    jbang GitHubElf.java --refresh       # fetch GitHub, take in ended agents, merge what's green; write the board
    jbang GitHubElf.java --dry-run       # fetch GitHub and print what the board would show; write nothing
    jbang GitHubElf.java --action=NAME   # one of the tab's actions, its values as JSON on stdin
    jbang GitHubElf.java --import=DB     # move the desk's data in

The runner refreshes it every 5 minutes on weekdays, 07:00 to 19:00, until you set another time on the desk.

## Moving your data in from the desk

With the desk stopped (or on a copy of its database file):

    jbang GitHubElf.java --import=$HOME/.local/share/house-elves/desk.mv.db

It moves in the threads with their turns, drafts, agents and files, the log of GitHub writes, merges, ignored
items, the scope and the settings. Agents that were still working are marked failed: the desk can't hand them
over. You can run it again: a thread changed here since stays as it is.

## Building and testing

    mvn test

Java 25, Jackson 2.18, JUnit 5. The tests use a temp directory and a fake GitHub.
