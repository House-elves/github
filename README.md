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

The board's own settings (its accounts, what it watches, the repos you maintain, refresh minutes) are at the top of
its Settings pop-up, drawn by the elf; the account, version, updates and removing it follow, from the desk.
They're kept in `state/`, not in the config file.

## More than one account

Under **Accounts** in its Settings you can add more `gh` accounts (each logged in on this machine with `gh auth
login`), and give each a name, such as Work or Personal. `GITHUB_ACCOUNT` is always the first. A refresh reads
GitHub as each of them, at the same time, and the board shows every item once. An item belongs to the account it's
most yours as: the one whose PR it is, then one asked to review it, then one it's assigned to, then one only involved
(the first account wins a tie). Its row is tagged with that account's name (+1 when another account has it too),
and the filter can show one account's items.

Everything you do with an item is done as the account it belongs to, and the desk's bar names it before anything is
written ("Close ... on GitHub, as phillip-kruger?"). If the item has since moved to another account, the elf
refuses instead. The names are in `state/accounts.json`, and which account each item belongs to in
`state/owners.json`; tokens still come from `gh` on each run and are never kept.

## What it reads and writes

It reads, as each of your `gh` accounts: the open issues and PRs you're involved in, with their CI, reviews and merge
state, and the runner's reports on its agents.

On your own PR, once you've pushed since someone asked for changes (or commented in a review), the next step is
**Ask them to review again**: it asks exactly those reviewers on GitHub, after your click in the desk's bar, and
the PR then waits on them. Pushing the agent's fixes to your PR offers the same as a box under **Push to GitHub**
(ticked): once the runner has pushed, those reviewers are asked again.

Each item's open panel and page start with its **Description**, folded: open it to read the issue's or PR's own
text from GitHub (read only then).

What the agents cost shows as **AI $…**: per item on its row and page (all its agent runs, failed ones too), and
for everything at the top of the board. It's Claude Code's own figure at API prices; on a Claude subscription
it's what it would have cost, not what you're billed.

An item in Your move can go to **Later** (the zz under its icon): it waits there, after Waiting on CI, until you
do something with it (start the agent, post, merge, ...) or click its zz again. It's kept in
`state/later.json`; nothing changes on GitHub.

Dependabot's org.mvnpm bumps aren't on the board: the mvnpm tab (the mvnpm-dependabot elf) handles them.

It writes its files in `~/.config/github/` (each written whole, then moved into place):

| Folder        | What                                                                                                    |
|---------------|---------------------------------------------------------------------------------------------------------|
| `board/`      | `board.json`, the board the tab shows; `cache-<login>.json`, what GitHub said at the last refresh, per account |
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
