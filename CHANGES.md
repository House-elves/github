# What's new

What changed for you in each version of the GitHub elf, newest first. The desk shows the parts that are new when it
offers you an update.

## 1.17.0

- A review's inline comment on a line the PR doesn't change (GitHub won't take it there) no longer stops the review
  from posting: it moves into the review's text, under its file and line, and the preview says so. Before, Look again
  didn't help when the PR hadn't changed: the agent kept its draft, and the same error came back.

## 1.16.2

- The SonarQube chip shows on every PR row of the board, next to its labels.

## 1.16.1

- The SonarQube chip only trusts SonarQube Cloud's own app, not a person with a look-alike name.

## 1.16.0

- A bot's comment (SonarQube Cloud's quality gate, a coverage report) no longer makes a PR you reviewed show as
  **Updated since your review**, and What's new leaves it out. Only people's reviews and comments, and new commits, count.
- SonarQube Cloud's quality gate shows on the PR's row: **SonarQube passed**, or **SonarQube failed**.

## 1.15.0

- A review drafted before the PR changed (its inline comments no longer in the diff) now has **Look again** and
  **Discard** under the error. Look again has the agent review the PR as it is now; its new draft replaces the old one.
- Errors no longer start with "Method [desk#elfAction] failed".

## 1.14.0

- Before Investigate or Fix on an issue, the thread lists other open PRs that mention it (title, author, draft,
  labels) and anyone else assigned, and the agent sees them too. The plan says where the root cause is, and
  recommends not going ahead when another PR covers it, someone claimed it, it's closed to outside contributors, or
  it's already fixed or not a bug.
- Fix reads the repo's CLAUDE.md, AGENTS.md, CONTRIBUTING.md and AI_POLICY.md, checks the default branch doesn't
  already fix it, fixes the root cause with a small change and one focused test that fails without it.
- PR descriptions end with a note that the change was written with an AI coding agent, and that you approved the
  plan and review it (you can still edit it before opening).
- Fix waits while you have 3 open PRs in a repo you don't maintain (change it, or turn it off, in Settings); the
  board says so on the issue.
- Labels show next to items on the board, and linked PRs show their title; the agent gives titles with every link and
  avoids wide tables. Reviews of others' PRs leave out summaries and nits.

## 1.13.0

- **Don't backport** on a merged PR takes it off the Merged list for good, when it doesn't need a backport. Nothing changes
  on GitHub. One that has its backport label already has **Done** instead, to clear it from the list.
- The Merged list's heading looks like the board's other folded sections.

## 1.12.0

- New: **Merged, to backport?** at the bottom of the board: the PRs you're involved in that were merged in the last
  7 days, in repos with backport labels (Quarkus' `triage/backport`, `triage/backport-3.40`...). **Backport…** lets you
  pick the branch; the PR is first tried as a cherry-pick onto it, and only a clean one is offered the label, which you
  confirm in the desk's bar. A conflict names the files, and no label goes on. `triage/backport` means the newest
  release branch (4.0 now).

## 1.11.0

- An issue your own open PR fixes is off the board while that PR is on it: the PR's row shows **Fixes #12** (a link to
  the issue) instead. If the PR closes without fixing it, the issue's row comes back.

## 1.10.1

- Your PRs waiting on others show Self-review as their button, so you can have the agent look over your own PR
  while you wait. It used to be under ⋯.

## 1.10.0

- New: voice input. A 🎤 next to "Ask the agent" and "Reply to the agent" on an item's thread: say it, and the words
  go in at the cursor. The desk listens, with your browser's speech recognition or Whisper on its machine. Needs Elf
  Desk 0.6.42.

## 1.9.4

- Later folds like Waiting on others: it starts closed, showing its count, and opens with a click (or from its
  name on the side, or when you filter).

## 1.9.3

- Filtering by account goes by each item's tag: an item shows under the account it's done as, in every section
  (Later, Waiting on CI, Waiting on others, Not watched, Ignored...), and the counts beside them follow.

## 1.9.2

- Repos I maintain say which of your accounts their items on the board are done as.

## 1.9.1

- In Settings, the orgs you watch and the suggested ones say which of your accounts have items there.
- The setup's GitHub account is now called Main GitHub account, and says it's the first one under Accounts, where you
  add more.

## 1.9.0

- More than one GitHub account: add them under **Accounts** in the board's Settings, each with a name you give it
  (Work, Personal). Each must be logged in to `gh` on this machine. The board shows what involves any of them, once.
- Each item is tagged with its account's name (and +1 when another account has it too). Filter by account next to the
  tags, or type the name in the filter.
- What you click is done as the account the item belongs to, and the desk's bar says which ("..., as phillip-kruger?").

## 1.8.0

- Quicker: a refresh no longer holds up your clicks. It used to keep everything waiting while it asked GitHub (a minute
  or two); now only the last second or so.
- Later and Ignore move the row at once, and you can go on to the next row straight away: a click on one row no
  longer greys out the same button on every other row.
- Next: on an item's page, a link to the next item in Your move, so you can go through them without going back to the
  board.

## 1.7.4

- Fixed: Approve CI run is offered on a contributor's PR after you've reviewed it, too, not only before.

## 1.7.3

- Fixed: Refresh no longer fails when GitHub is slow to answer on a big board. It now waits up to 4 minutes.

## 1.7.2

- An item you start working on from Just involved is now yours, and moves to Your move.

## 1.7.1

- A review you post from the desk counts straight away, without waiting for the next refresh.

## 1.7.0

- See what the AI work cost, for each item and for the whole board.

## 1.6.0

- Each item has a Description you can unfold, with its own text from GitHub.

## 1.5.0

- Push and ask the reviewers again, in one click.

## 1.4.0

- Ask reviewers to look again once you've pushed since their review.

## 1.3.1

- Items set aside for later show a small zz under their icon.

## 1.3.0

- Later: put an item in Your move aside until you do something with it.

## 1.2.1

- mvnpm bumps stay in the mvnpm tab.
- Comments show as plain text.
- Hide hides an item on your board; it doesn't close it on GitHub.

## 1.2.0

- One Settings pop-up: the board's settings sit at the top of the desk's settings.

## 1.1.1

- Issues in repos you maintain are your move, unless someone else is assigned.

## 1.1.0

- mvnpm bumps moved to the mvnpm-dependabot elf's own tab.

## 1.0.0

- The GitHub board, its settings and each item's thread, in the GitHub elf's tab.
