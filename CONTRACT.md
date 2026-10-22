# The GitHub elf: what it must do (phase 5 of House-elves/elf-desk#1)

The desk's GitHub board moves out of Elf Desk into this elf. The desk keeps no GitHub token and no GitHub code. This
elf owns the GitHub account, the token, the board's settings and state, and the board's tab. Everything the
desk's GitHub tab does today keeps working the same way, for the person using it.

The source of truth for behaviour is the desk as it is now: `~/Projects/House-elves/elf-desk`, packages
`io.github.houseelves.desk.{github,board,thread,agent,merge,ci,conflicts,scope,ignore,settings,mvnpm}`,
`runner/GitHubWriter.java`, and the board's half of `api/DeskApi.java`, with their tests. Port that logic, don't
redesign it. Where the desk used its database, CDI, Quarkus REST client or the runner, use what's below.

## Shape

- A Maven layout: `src/main/java/io/github/houseelves/github/...`, `src/test/java/...`, a `pom.xml` (Java 25, Jackson
  2.18.x, JUnit 5) for building and testing with `mvn test`.
- `GitHubElf.java` at the root is the elf's `main` for jbang: `//JAVA 25+`, the same `//DEPS` as the pom, and
  `//SOURCES src/main/java/**/*.java`. It parses its arguments and calls into the packages.
- No Quarkus, no CDI, no database. Plain classes with constructors; `java.net.http.HttpClient` for GitHub (GraphQL
  and REST); Jackson for JSON.
- Its config file is `~/.config/github/config` (KEY=value lines, as the other elves read theirs; see
  `~/Projects/House-elves/mail-triage/Config.java`). Its data is in folders next to it, in `~/.config/github/`.
- Tests: port the desk's tests for the moved code (FlowClassifierTest, BoardClassifierTest, BoardServiceTest,
  GitHubTest, MergeDecisionTest, MergeServiceTest, CiServiceTest, ConflictServiceTest, ScopeServiceTest, ScopeTest,
  IgnoreServiceTest, PromptsTest, ThreadFlowTest, ReviewLinesTest, AssignTest, GitHubWriterTest, MvnpmElfTest,
  MvnpmRunsTest, the board half of DeskApiTest) onto the new classes, with a temp directory for the files and a fake
  GitHub (the desk's tests show what they fake). They must pass with `mvn test`.

## The token

- Setting `GITHUB_ACCOUNT` (kind `login`): the `gh` account the elf works as. The token is `gh auth token -u
  <account>`, read fresh for each run, never written anywhere, never logged.
- The elf's GitHub identity is that account (it replaces the desk's `elfdesk.github-user` and `CurrentUser.login()`).

## Files (data folders the tab reads; all JSON, written whole then moved into place)

- `board/board.json`: the board, the same JSON as the desk's `DeskApi.board()` returns today (`Board` with
  `with(...)`, `mvnpmChecks(...)` and `withFlows(now)` applied). `runnerConnected` is always true. Also the cache it's
  built from (`board/cache.json`: what `BoardService` keeps in memory now), so actions can rebuild `board.json` from
  local state without asking GitHub.
- `threads/<key>.json`, one per item that has a thread, `<key>` being the item key with every character outside
  `[A-Za-z0-9._-]` made `_`: the same JSON as `DeskApi.thread(itemKey)` (`ThreadView`). Its `job` is the agent working
  on it now (see Agents), in the same `JobView` shape, with `lines` from the agent's report. Agent files are listed
  per turn as today (`TurnView.files`), named as the runner keeps them (`agents/<id>--<name>`).
- `state/`: what the desk kept in its tables: `scope.json` (watch_scope), `settings.json` (setting), `ignored.json`
  (ignored_item), `merges.json` (merge_watch), `actions.json` (github_action, the log of what was written to GitHub),
  `threads-index.json` if useful. `state/settings-view.json`: the same JSON as `DeskApi.settings()`.
- `cache/`: answers too big for an action's one output line (a PR's diff, its activity, a CI report): the action writes
  `cache/<name>.json` and answers `{"file": "<name>.json"}`.

## Commands (run in the background by the runner; elf.yaml `commands`)

- `refresh` (`[--refresh]`): fetches GitHub as `BoardService.board(login, token, refresh=true)` does, writes the cache
  and `board.json`. Also takes in any ended agents not taken in yet, and runs the merge-on-green watch once
  (`MergeWatch`/`MergeService`: merges what's green and was asked to merge). This is the elf's `refresh`, scheduled
  every 5 minutes on weekdays from 07:00 to 19:00 by default (12 calendar rules joined with `;`, e.g.
  `Mon..Fri *-*-* 7..19:00; Mon..Fri *-*-* 7..19:05; ...`).
- `dry-run` (`[--dry-run]`): fetches and prints a summary, writes nothing.

## Actions (elf.yaml `actions`; params come as JSON on stdin; the answer is the last line printed, JSON)

Every board method of `DeskApi` becomes an action with the same params and the same answer JSON (big answers via
`cache/`). After an action that changes what the board shows, it rewrites `board.json` from the cache and local state
(no GitHub fetch unless the method fetched before). Names are the method names in kebab case.

Reads (`ask: false`): `item`, `about`, `thread`, `pr-diff`, `pr-activity`, `pr-reviews`, `ci-report`,
`conflict-options`, `commit-count`, `merge-options`, `merge-status`, `suggestions`, `preview-post`, `settings`,
`is-ignored`.

Local changes (`ask: false`): `update-draft`, `discard-draft`, `ignore`, `unignore`, `cancel-merge`,
`watch`, `watch-all`, `exclude`, `remove`, `add-always-involved`, `remove-always-involved`, `set-mvnpm-enabled`,
`set-refresh-minutes`, `seen` (the thread page was looked at), and the agent starts below.

Agent starts (`ask: false`; they write nothing to GitHub): `start` (with `action` and `question`), `fix-review`,
`reply`, `ci-why`, `ci-why-again`, `ci-fix`, `address-review`, `resolve-conflicts`, `cancel` (stops the item's agent:
answers with `{"cancelAgent": id}` and the tab calls `elf.cancelAgent(id)`; the thread notes it).

GitHub writes (`ask: true`, with a `confirm` text naming what happens, e.g. `Post this {kind} on {item}?`): `post`
(review, comment, issue, push, pr: as `Poster.post`), `close`, `ci-reopen`, `ready-for-review`, `assign-me`, `ci-rerun`,
`ci-approve`, `ask-dependabot-to-rebase`, `update-branch`, `squash`, `merge-on-green`, `merge-without-approval`,
`mvnpm-run`. The desk's rule stays: nothing is written to GitHub without the person's click, and that click is the
desk's confirm bar for these actions.

- Pushing an agent's commits: the elf never runs git in the runner's worktrees. It answers with
  `{"push": {repo, number, headRepo, headRef, from, to, squash, base, token}}` (the same fields as the desk's
  `git.push` message today, plus its token), and the runner pushes from the worktree with its checks and answers in
  place of it with `{"push": {status, error?}}`. The runner then gives that answer to the tab, so the action that
  asked for a push must also have saved, before printing, whatever the thread needs to say once it's pushed; the tab
  calls `post-done` (`ask: false`, with the draft id and the push result) to finish the post (the PR's head, the
  posted mark, the board, and for a new PR, opening it). `post-done` only finishes a post that `post` started: `post`
  leaves a pending mark on the draft (with the commits and when), and `post-done` refuses without one, or one older
  than 15 minutes, or one for other commits. It never writes to GitHub otherwise.
- Squashing (`squash`): answers with `{"squash": checkout}`; the runner squashes in the worktree, keeps the squashed
  commit in `agents/squash-<n>.json` and answers with `{"squash": {status, file}}`; then the tab calls `squash-done`
  with it, as above.

## Agents (elf.yaml `agents: {done: agent-done}`)

- An agent start builds the prompt exactly as `ThreadService`/`Prompts` do now (the item's text, CI logs, review
  comments, fetched with the token), saves the thread (the person's turn, the job as queued), and answers with
  `{"agent": {id, checkout: {repo, number, kind, reset?}, prompt, untrusted, schema: AgentOutput.SCHEMA, session}}`.
  The runner starts it and answers with `{"agent": {id, status}}` in its place. `id`: `[a-z0-9-]`, e.g.
  `<key-slug>-<millis>`.
- The runner reports in `agents/<id>.json`: `{id, status: queued|running|done|failed|cancelled, lines, output, session,
  git, cost, files, error, created, started, finished}`. While it runs, the tab reads it for the live lines.
- When it ends, the runner calls the action `agent-done` with `{"id": ...}`: the elf takes the report in the way
  `ThreadService.jobDone/jobFailed` does today (the agent's turn, its draft from the output, the commits as a push or
  PR draft, the review's fixes, the Claude session for follow-ups, the files), then rewrites the thread and the board.
- The agent's job time limit, model and the safety check stay the runner's.

## Settings (elf.yaml `settings`)

`GITHUB_ACCOUNT` (login, needed). The board's own settings stay where they are in the desk's UI (the board's
Settings panel: scope, always involved, mvnpm on/off, refresh minutes) and live in `state/settings.json` and
`state/scope.json`, not in the config file.

## mvnpm

The mvnpm lane reads `mvnpm-dependabot`'s state as the desk does now (`MvnpmElf`), and `mvnpm-run` starts a check the
way the runner's `mvnpm.run` does today (`systemd-run --user ...` of mvnpm-dependabot, Linux only), done by this elf
directly.

## Moving the person's data in (`import`, a command, not in elf.yaml)

`GitHubElf.java --import=<path to the desk's H2 file>`: reads the desk's tables (threads, turns, drafts, jobs, job
files, github actions, merge watches, ignored items, scope, settings) and writes the files above. Use the H2 driver
(`com.h2database:h2` at the desk's version) only for this. Jobs that are open become failed ("The desk moved this to
the GitHub elf"). Re-runnable: it never overwrites a thread file newer than the database row.

## Not in this elf

The desk's sign-in, sessions, elf frames, the other elves, the runner's job engine and sandbox.
