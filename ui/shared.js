// What the board's views share, from the desk's shared.js: the PR and issue icon, the status chips, the checks
// behind Merge and the CI and conflict menus, thread links, and the items seen on the board. The kit has the rest
// (styles, markdown, ago, spinner, Ticking).
import { html, svg, spinner, count, joinParts } from '/elf-kit/1/kit.js';

/** The Settings button in a tab's title row: opens the board's settings above its content. */
export function settingsToggle(open, onClick) {
    return html`<button class="quiet settings-toggle" aria-expanded=${open ? 'true' : 'false'} @click=${onClick}>
        <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" aria-hidden="true">
            <path d="M2 4h7M12.5 4H14M2 8h2.5M8 8h6M2 12h7M12.5 12H14"/>
            <circle cx="10.75" cy="4" r="1.6"/><circle cx="6.25" cy="8" r="1.6"/><circle cx="10.75" cy="12" r="1.6"/>
        </svg>Settings</button>`;
}

/**
 * What AI cost, in US dollars: "$0.42" ("<$0.01" for less). Claude Code's own figure, at API prices: on a Claude
 * subscription it's what it would have cost, not what you're billed.
 */
export function usd(n) {
    const v = Number(n);
    if (!Number.isFinite(v)) return '';
    return v > 0 && v < 0.01 ? '<$0.01' : `$${v.toFixed(2)}`;
}

/** What it says when you hover over a cost. */
export const COST_HINT = "What the agents cost so far: Claude Code's figure at API prices (on a subscription, not what you're billed)";

/** PR or issue icon, coloured by state: CI for PRs, open/closed for issues. */
export function kindIcon(item) {
    if (item.kind === 'PR') {
        const cls = { SUCCESS: 'ok', FAILURE: 'bad', ERROR: 'bad', PENDING: 'run', EXPECTED: 'run' }[item.ci] ?? 'idle';
        return html`<svg class="icon ${cls}" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6"
                        stroke-linecap="round" role="img" aria-label="Pull request${ciLabel(item.ci)}">
            ${svg`<circle cx="4" cy="3.5" r="1.8"/><circle cx="4" cy="12.5" r="1.8"/><circle cx="12" cy="12.5" r="1.8"/>
                  <path d="M4 5.3v5.4M12 10.7V6.5a2 2 0 0 0-2-2H7.5M9 2.8 7.3 4.5 9 6.2"/>`}
        </svg>`;
    }
    return html`<svg class="icon idle" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6"
                    role="img" aria-label="Issue">
        ${svg`<circle cx="8" cy="8" r="6"/><circle cx="8" cy="8" r="1.4" fill="currentColor" stroke="none"/>`}
    </svg>`;
}

function ciLabel(ci) {
    return { SUCCESS: ', CI passing', FAILURE: ', CI failing', ERROR: ', CI failing', PENDING: ', CI running',
        EXPECTED: ', CI running' }[ci] ?? '';
}

/** GitHub says every required check passed and nothing else blocks the merge. */
export function requiredChecksPassed(item) {
    return item.mergeState === 'CLEAN' || item.mergeState === 'HAS_HOOKS';
}

/** Approved, green, and every required check passed: the only time Merge is offered. */
export function mergeable(item) {
    return item.kind === 'PR' && item.approved === true && item.ci === 'SUCCESS' && !item.draft && requiredChecksPassed(item);
}

/** Green but not approved: "Merge without approval" is offered (on the row for your own PRs, otherwise under More). */
export function mergeableAnyway(item) {
    return item.kind === 'PR' && item.approved !== true && item.ci === 'SUCCESS' && !item.draft && requiredChecksPassed(item);
}

/** A fork PR whose CI waits for a maintainer to approve it ("Approve and run"). */
export function ciNeedsApproval(item) {
    return item.kind === 'PR' && item.ci === 'AWAITING_APPROVAL';
}

export function ciFailing(item) {
    return item.kind === 'PR' && (item.ci === 'FAILURE' || item.ci === 'ERROR');
}

/** Behind its base, and the repo requires up-to-date branches (no conflicts: those have their own menu). */
export function behind(item) {
    return item.kind === 'PR' && item.mergeState === 'BEHIND' && item.mergeable !== 'CONFLICTING';
}

/** Opened by Dependabot: "@dependabot rebase" is how it gets rebased (it keeps maintaining the PR). */
export function isDependabot(item) {
    return item.kind === 'PR' && /^(app\/)?dependabot(\[bot\])?$/.test(item.author ?? '');
}

export function hasConflicts(item) {
    return item.kind === 'PR' && item.mergeable === 'CONFLICTING';
}

/**
 * Status chips for a row. CI is already the icon's colour, so only what the icon can't say. With
 * {@code ciMenu}, failing CI is left out so the caller can show the CI menu in its place.
 *
 * @param approval false leaves out "Not approved"
 */
export function itemChips(item, { ciMenu = false, conflictMenu = false, approval = true } = {}) {
    const chips = [];
    if (item.kind === 'PR') {
        if (item.draft) chips.push(html`<span class="chip">Draft</span>`);
        if (ciFailing(item) && !ciMenu) chips.push(html`<span class="chip bad">CI failing</span>`);
        if (ciNeedsApproval(item) && !ciMenu) chips.push(html`<span class="chip warn">CI needs approval</span>`);
        // Green so far, but GitHub's required checks haven't all passed (often: the real CI hasn't started yet).
        if (item.ci === 'SUCCESS' && item.mergeState === 'BLOCKED' && !item.draft
                && item.reviewDecision !== 'REVIEW_REQUIRED' && item.reviewDecision !== 'CHANGES_REQUESTED') {
            chips.push(html`<span class="chip run" title="GitHub says required checks haven't all passed yet">Waiting for required checks</span>`);
        }
        if (behind(item) && !conflictMenu) chips.push(html`<span class="chip warn" title="The repo requires the branch to be up to date before merging">Behind base</span>`);
        if (item.ci === 'PENDING' || item.ci === 'EXPECTED') chips.push(html`<span class="chip run">${spinner}CI in progress</span>`);
        if (item.ci === 'SUCCESS' && item.mergeState !== 'BLOCKED') {
            chips.push(html`<span class="chip ok" title="Every check on the latest commit passed">CI passed</span>`);
        }
        if (item.reviewDecision === 'APPROVED') chips.push(html`<span class="chip ok">Approved</span>`);
        // Green and ready, apart from an approval: say so, as that's what decides between Merge and Merge without approval.
        if (approval && mergeableAnyway(item) && item.reviewDecision !== 'CHANGES_REQUESTED') {
            chips.push(html`<span class="chip" title="Nobody with write access has approved it yet">Not approved</span>`);
        }
        if (item.reviewDecision === 'CHANGES_REQUESTED') chips.push(html`<span class="chip warn">Changes requested</span>`);
        if (hasConflicts(item) && !conflictMenu) chips.push(html`<span class="chip bad">Conflicts</span>`);
    }
    return chips;
}

// ---- the board in one sentence (the board's title, and the dashboard card) ----

/** How many items wait on each: { you: 3, agent: 1, ... }. */
export function waitingCounts(b) {
    const n = {};
    Object.values(b?.flows ?? {}).forEach(f => { n[f.waiting] = (n[f.waiting] ?? 0) + 1; });
    return n;
}

/**
 * Where things stand, in one sentence: "3 things need your move, an agent is working on 1 and 2 wait on CI."
 * `link(sectionId, text)` renders the counts that lead to a section.
 */
export function boardHeadline(b, link) {
    const n = waitingCounts(b);
    const parts = [];
    if (n.you) parts.push(html`${link('you', count(n.you, 'thing'))} ${n.you === 1 ? 'needs' : 'need'} your move`);
    if (n.agent) parts.push(html`${parts.length ? 'an' : 'An'} agent is working on ${link('agent', count(n.agent, 'item'))}`);
    if (n.ci) parts.push(html`${link('ci', count(n.ci, 'item'))} ${n.ci === 1 ? 'waits' : 'wait'} on CI`);
    if (parts.length === 0) return 'Nothing needs you right now.';
    return html`${joinParts(parts)}.`;
}

// ---- places in the tab: the board, and one item's thread page (thread/owner/repo/123) ----

/** owner/repo#123 -> thread/owner/repo/123, the thread page's route in the tab (the desk's link: #github/thread/...). */
export function threadRoute(itemKey) {
    const [repo, number] = itemKey.split('#');
    return `thread/${repo}/${number}`;
}

/** A link to the thread page. The tab turns a click on it into its route (see board.js); the desk's own was the same. */
export function threadHref(itemKey) {
    return `#${threadRoute(itemKey)}`;
}

/** thread/owner/repo/123 -> owner/repo#123, or null when the route isn't a thread page. */
export function itemKeyFromRoute(route) {
    const m = /^thread\/([^/]+\/[^/]+)\/(\d+)$/.exec(route ?? '');
    return m ? `${m[1]}#${m[2]}` : null;
}

/** The thread page of this item is the one showing (the menus act differently there and on the board). */
export function onThreadPage(itemKey, route) {
    return route === threadRoute(itemKey);
}

// ---- what the board knows ----

/** The last board this tab read: going back to the board shows it at once while it updates. */
export let lastBoard = null;
export function setLastBoard(b) {
    lastBoard = b;
}

/** The item is in the board's Ignored. */
export function boardIgnores(itemKey) {
    return (lastBoard?.ignored ?? []).some(i => i.key === itemKey);
}

/** Items seen on the board, so a new thread page knows the title and kind before any thread exists. */
const seen = new Map();
export function rememberItem(item) {
    seen.set(item.key, item);
}
export function knownItem(itemKey) {
    return seen.get(itemKey);
}

/** Every item on a board, remembered: the lanes, the ignored and the not watched. */
export function rememberBoard(b) {
    if (!b) return;
    [...(b.lanes ?? []).flatMap(l => l.items ?? []), ...(b.hidden ?? []),
        ...(b.ignored ?? [])].forEach(i => { if (i?.key) rememberItem(i); });
    rememberLinks(b.linkedPrs);
}

/**
 * Open PRs that fix or mention each issue ({key, title, url, author, draft, fixes}), from the board, so the
 * issue's thread page can list them. Remembers each PR too, so its thread page knows it's a PR.
 */
const links = new Map();
export function rememberLinks(linkedPrs) {
    links.clear();
    for (const [issueKey, prs] of Object.entries(linkedPrs ?? {})) {
        links.set(issueKey, prs);
        for (const pr of prs) {
            if (!seen.has(pr.key)) {
                const [repo, number] = pr.key.split('#');
                seen.set(pr.key, { key: pr.key, repo, number: Number(number), kind: 'PR', title: pr.title, url: pr.url,
                    author: pr.author, draft: pr.draft });
            }
        }
    }
}
export function linksFor(issueKey) {
    return links.get(issueKey) ?? [];
}

/** "Fixed by #12" or "Mentioned in #12" chips, linking to the PR's thread here. */
export function linkedPrChips(issueKey) {
    return linksFor(issueKey).slice(0, 3).map(pr => html`<a class="chip ${pr.fixes ? 'run' : ''}" href=${threadHref(pr.key)}
        title="${pr.title} (by ${pr.author}${pr.draft ? ', draft' : ''})">${pr.fixes ? 'Fixed by' : 'Mentioned in'} ${prRef(issueKey, pr.key)}</a>`);
}

/** #12 in the same repo, owner/repo#12 elsewhere. */
export function prRef(fromKey, prKey) {
    return fromKey.split('#')[0] === prKey.split('#')[0] ? '#' + prKey.split('#')[1] : prKey;
}
