// The desk's GitHub board (desk-board.js), in the GitHub elf's tab: the same lanes, filter, rows and keys, read from
// board/board.json, with every call one of the elf's actions. Nothing here writes to GitHub on its own: the steps
// that do wait for your click in the desk's bar.
import { LitElement, html, css, shared, ago, spinner, Ticking, count, elf } from '/elf-kit/1/kit.js';
import {
    linkedPrChips, rememberLinks, itemChips, ciFailing, hasConflicts, mergeableAnyway, kindIcon, threadHref, rememberItem,
    settingsToggle, isDependabot, lastBoard, setLastBoard, boardHeadline,
} from './shared.js';
import { act, board, cancelled, errorMessage, squash, startAgent } from './api.js';
import './merge-control.js';
import './ci-menu.js';
import './conflict-menu.js';
import './pr-activity.js';
import './pr-review.js';
import './gh-thread.js';
import './gh-settings.js';

const DATE = new Intl.DateTimeFormat('en-AU', { weekday: 'long', day: 'numeric', month: 'long' });

/** The board's sections, by whose move it is (Flow.waiting). Folded ones start closed. */
const SECTIONS = [
    { id: 'you', title: 'Your move', empty: 'Nothing needs you right now.' },
    { id: 'agent', title: 'Agent working' },
    { id: 'ci', title: 'Waiting on CI' },
    { id: 'others', title: 'Waiting on others', fold: true },
    { id: 'parked', title: 'Draft PRs', fold: true },
    { id: 'done', title: 'Done', fold: true },
    { id: 'watching', title: 'Just involved', fold: true },
];

const ROLES = { mine: 'My PR', review: 'Review', issue: 'Issue', involved: 'Involved' };

/** A row's tag. "Just involved" says how you're involved in its heading, so its rows say what they are. */
function roleTag(item, role) {
    if (role === 'involved') return item.kind === 'PR' ? 'PR' : 'Issue';
    return ROLES[role] ?? role;
}

/**
 * The board's filter: the text, and the tags picked. The tab's frame has no storage of its own, so the desk keeps it
 * in this browser (elf.store), and this frame keeps it while it's open.
 */
const FILTER = 'board-filter';
let filter = null;

async function loadFilter() {
    try {
        const f = JSON.parse((await elf.store.get(FILTER)) ?? 'null');
        return { text: f?.text ?? '', tags: new Set(f?.tags ?? []) };
    } catch { return { text: '', tags: new Set() }; }
}

/**
 * Whether an item passes the filter: every word of the text is in its title, repo, number, tag or author, and
 * its tag is one of those picked (when any are). Items with no tag (not watched, ignored) pass only without tags.
 */
export function matches(item, role, filter) {
    if (filter.tags.size && !filter.tags.has(role)) return false;
    const words = filter.text.toLowerCase().split(/\s+/).filter(Boolean);
    if (!words.length) return true;
    const hay = [item.title, item.repo, `#${item.number}`, ROLES[role] ?? role ?? '', item.author ?? ''].join(' ').toLowerCase();
    return words.every(w => hay.includes(w));
}

/**
 * Steps run straight from a row, as the elf's actions: agent steps leave the row showing the agent working; the CI,
 * rebase, ready and assign steps write to GitHub, so they wait for your click in the desk's bar.
 */
const CALLS = {
    review: key => startAgent('start', { itemKey: key, action: 'review' }),
    investigate: key => startAgent('start', { itemKey: key, action: 'investigate' }),
    fix: key => startAgent('start', { itemKey: key, action: 'fix' }),
    'ci-why': key => startAgent('ci-why', { itemKey: key }),
    'ci-fix': key => startAgent('ci-fix', { itemKey: key }),
    'ci-rerun': key => act('ci-rerun', { itemKey: key }),
    'ci-reopen': key => act('ci-reopen', { itemKey: key }),
    'ci-approve': key => act('ci-approve', { itemKey: key }),
    conflicts: key => startAgent('resolve-conflicts', { itemKey: key }),
    'dependabot-rebase': key => act('ask-dependabot-to-rebase', { itemKey: key }),
    ready: key => act('ready-for-review', { itemKey: key }),
    address: key => startAgent('address-review', { itemKey: key }),
    'self-fix': key => startAgent('fix-review', { itemKey: key }),
    assign: key => act('assign-me', { itemKey: key }),
};

/** Steps that start the agent or CI, which an open row's panel offers next to the agent's answer. */
const PANEL_STEPS = new Set(['self-fix', 'ci-fix', 'ci-why', 'ci-rerun', 'ci-reopen', 'conflicts', 'address', 'fix', 'investigate', 'review']);

/**
 * The GitHub board, by whose move it is. Every item is in a flow (your PR, a PR to review, an assigned issue); the
 * desk works out its stage and next step (FlowClassifier), and the row shows that with one main
 * button. A row opens in place to read what the agent made and send it, so you rarely leave the board.
 * Keys: j/k move between rows, Enter runs the main step, o opens or closes the row.
 */
class GhBoard extends LitElement {

    static properties = {
        /** The board's settings are open (what to watch, the runner). */
        settingsOpen: { type: Boolean },
        _board: { state: true },
        _loading: { state: true },
        /** The elf's runs (elf.state()): a refresh running, or the end of a failed one's log. */
        _state: { state: true },
        _starting: { state: true },
        _error: { state: true },
        _rowError: { state: true },   // {key, text}: a step that failed, shown on its row
        _open: { state: true },
        _doing: { state: true },
        _watching: { state: true },
        _closing: { state: true },
        _confirmClose: { state: true },
        _moreFor: { state: true },
        _mergeAnyway: { state: true },
        /** What the open row shows under it: thread (the agent), review (Read the review) or activity (What's new). */
        _panel: { state: true },
        _ignoring: { state: true },
        _unfolded: { state: true },
        _filter: { state: true },
    };

    static styles = [shared, css`
        .hero { display: grid; gap: 10px; max-width: 46rem; }
        .board-top { margin-bottom: 34px; }
        /* A pop-up at the top of the tab (where its Settings button is): the frame is as tall as the board, so a
           dialog centred in it could be far below. */
        .settings-dialog { position: absolute; top: 16px; margin: 0 auto; width: min(52rem, calc(100% - 32px));
            max-height: none; padding: 22px 24px 26px; border: 1px solid var(--line); border-radius: var(--r-panel);
            background: var(--bg); color: var(--ink); box-shadow: var(--shadow); }
        .settings-dialog::backdrop { background: color-mix(in srgb, var(--ink) 35%, transparent); }
        .settings-dialog[open] { display: grid; gap: 22px; }
        .dialog-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
        .dialog-head h2 { font-size: 22px; font-weight: 600; margin: 0; }
        .date { color: var(--muted); font-size: 15px; }
        .hero h1 { font-size: 34px; line-height: 1.18; font-weight: 600; letter-spacing: -0.022em; text-wrap: balance; }
        .hero h1 button {
            all: unset; cursor: pointer; text-decoration: underline; text-decoration-thickness: 2px;
            text-underline-offset: 5px; text-decoration-color: var(--line);
        }
        .hero h1 button:hover { text-decoration-color: var(--accent); }
        .hero h1 button:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; border-radius: 4px; }
        .status { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 16px; margin-top: 6px; font-size: 14px; }
        .runner { display: inline-flex; align-items: center; gap: 7px; color: var(--muted); }
        .runner::before { content: ""; width: 8px; height: 8px; border-radius: 50%; background: var(--idle); }
        .runner.on::before { background: var(--ok); box-shadow: 0 0 0 3px var(--ok-soft); }
        .updated { display: inline-flex; align-items: center; gap: 7px; }
        .confirm-close {
            display: flex; flex-wrap: wrap; align-items: center; gap: 8px; justify-content: flex-end;
            font-size: 13px; color: var(--ink);
        }

        .layout { display: grid; grid-template-columns: 200px minmax(0, 1fr); gap: 36px; align-items: start; }
        aside { position: sticky; top: 84px; display: grid; gap: 2px; }
        aside button {
            justify-content: space-between; border: 0; background: transparent; padding: 8px 12px;
            font-weight: 500; color: var(--muted); width: 100%;
        }
        aside button:hover:not(:disabled) { color: var(--ink); }
        aside button .n { color: var(--ink); font-weight: 600; font-variant-numeric: tabular-nums; }
        aside button.zero .n { color: var(--muted); font-weight: 500; }
        aside button.you:not(.zero) { color: var(--ink); }
        aside hr { border: 0; border-top: 1px solid var(--line); margin: 10px 12px; }
        .filter { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 14px; }
        .filter .field { position: relative; flex: 1 1 20rem; max-width: 36rem; min-width: 0; }
        .filter .field > svg {
            position: absolute; left: 13px; top: 50%; translate: 0 -50%; width: 16px; height: 16px;
            color: var(--muted); pointer-events: none;
        }
        .filter input[type="search"] {
            width: 100%; box-sizing: border-box; height: 40px; padding: 0 112px 0 38px;
            font: 15px/1 var(--font); color: var(--ink); background: var(--surface);
            border: 1px solid var(--line); border-radius: 999px; appearance: none;
            transition: border-color 120ms, box-shadow 120ms;
        }
        .filter input[type="search"]::-webkit-search-cancel-button { appearance: none; }
        .filter input[type="search"]::placeholder { color: var(--muted); }
        .filter input[type="search"]:hover { border-color: color-mix(in srgb, var(--muted) 45%, var(--line)); }
        .filter input[type="search"]:focus { outline: none; border-color: var(--accent); box-shadow: 0 0 0 3px var(--accent-soft); }
        .filter .field:focus-within > svg { color: var(--accent); }
        .filter .end { position: absolute; right: 8px; top: 50%; translate: 0 -50%; display: flex; align-items: center; gap: 6px; }
        .filter .end kbd {
            font: 12px/1 var(--font-code); color: var(--muted); padding: 3px 7px; border-radius: 6px;
            border: 1px solid var(--line); background: var(--surface-2);
        }
        .filter .count { font-size: 12.5px; color: var(--muted); font-variant-numeric: tabular-nums; white-space: nowrap; }
        .filter .clear {
            width: 26px; height: 26px; padding: 0; border: 0; border-radius: 999px; background: var(--surface-2); color: var(--muted);
        }
        .filter .clear:hover:not(:disabled) { color: var(--ink); background: var(--line); }
        .filter .clear svg { width: 12px; height: 12px; }
        .filter .tags { display: flex; flex-wrap: wrap; gap: 6px; }
        .filter .tag { border-radius: 999px; }
        .filter .tag .n { color: var(--muted); font-variant-numeric: tabular-nums; margin-left: 2px; }
        .filter .tag[aria-pressed="true"] { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); }
        .filter .tag[aria-pressed="true"] .n { color: var(--accent); }
        .keys { font-size: 12px; color: var(--muted); padding: 10px 12px 0; line-height: 1.6; }
        kbd { font: 11px var(--font-code); border: 1px solid var(--line); border-radius: 4px; padding: 0 4px; }

        .sections { display: grid; gap: 34px; min-width: 0; }
        .sec-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
        .sec-head h2 { font-size: 18px; font-weight: 600; letter-spacing: -0.01em; }
        .sec-head .n { color: var(--muted); font-size: 15px; }
        section { scroll-margin-top: 90px; }
        section.you > .panel { box-shadow: inset 3px 0 0 var(--accent); }

        ul { list-style: none; margin: 0; padding: 4px 0; }
        li {
            display: grid; grid-template-columns: 16px minmax(0, 1fr) auto; gap: 4px 14px; align-items: start;
            padding: 12px 18px;
        }
        li + li { border-top: 1px solid var(--line); }
        li:hover { background: color-mix(in srgb, var(--surface-2) 55%, transparent); }
        li:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }
        li.open { background: color-mix(in srgb, var(--surface-2) 70%, transparent); }
        li .icon { margin-top: 3px; }
        .main { min-width: 0; display: grid; gap: 5px; cursor: pointer; }
        .line1 { display: flex; align-items: baseline; gap: 8px; min-width: 0; }
        .role {
            flex: none; font-size: 11.5px; font-weight: 600; color: var(--muted); border: 1px solid var(--line);
            border-radius: 999px; padding: 0 7px; line-height: 18px;
        }
        .title { color: var(--ink); font-weight: 500; text-decoration: none; overflow-wrap: anywhere; }
        .author {
            flex: none; font-size: 12px; color: var(--muted); text-decoration: none;
            background: var(--surface-2); border-radius: 999px; padding: 0 8px; line-height: 19px;
            max-width: 16ch; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
        }
        .author:hover { color: var(--accent); background: var(--accent-soft); }
        .title:hover { text-decoration: underline; }
        .meta { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 12px; font-size: 13px; color: var(--muted); }
        button.chip.squash { border: 0; cursor: pointer; font: inherit; font-size: 12.5px; font-weight: 500; line-height: 1; gap: 8px; }
        button.chip.squash span { color: var(--accent); }
        button.chip.squash:hover { background: var(--accent-soft); color: var(--accent); }
        .row-error { margin: 8px 0 0; font-size: 13.5px; display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: center; }
        .say { color: var(--ink); }
        .steps { display: inline-flex; align-items: center; gap: 6px; }
        .seg { display: inline-flex; gap: 3px; }
        .seg i { width: 14px; height: 4px; border-radius: 2px; background: var(--line); }
        .seg i.done { background: color-mix(in srgb, var(--accent) 45%, var(--line)); }
        .seg i.now { background: var(--accent); }
        .stage { font-weight: 500; }
        .acts { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; align-items: center; max-width: 26rem; }
        .inline { grid-column: 2 / -1; padding: 8px 0 4px; min-width: 0; }
        .empty { color: var(--muted); padding: 16px 18px; margin: 0; font-size: 14px; }

        .notice { padding: 18px 20px; margin-bottom: 26px; display: grid; gap: 8px; max-width: 46rem; }
        .elf-settings { display: grid; gap: 10px; }
        .general { padding: 14px 18px; display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
        .notice p { margin: 0; }

        details summary {
            cursor: pointer; color: var(--muted); font-weight: 500; padding: 4px 0; list-style-position: outside;
        }
        details summary:hover { color: var(--ink); }
        details summary .n { font-variant-numeric: tabular-nums; }
        details[open] summary { margin-bottom: 12px; }

        .sk-row { display: grid; grid-template-columns: 16px 1fr 90px; gap: 14px; padding: 15px 18px; }
        .sk-row + .sk-row { border-top: 1px solid var(--line); }
        .sk-line { height: 12px; }

        @media (max-width: 960px) {
            .layout { grid-template-columns: minmax(0, 1fr); gap: 24px; }
            aside { position: static; display: flex; flex-wrap: wrap; gap: 6px; }
            aside button { width: auto; gap: 8px; background: var(--surface); border: 1px solid var(--line); padding: 6px 12px; }
            aside hr, .keys { display: none; }
        }
        @media (max-width: 600px) {
            .hero h1 { font-size: 26px; }
            li { grid-template-columns: 16px minmax(0, 1fr); padding: 12px 14px; }
            .acts { grid-column: 2; justify-content: flex-start; }
            .inline { grid-column: 1 / -1; }
        }
    `];

    constructor() {
        super();
        new Ticking(this);
        this._board = lastBoard;
        this._loading = !lastBoard;
        this._open = null;
        this._doing = null;
        this._watching = null;
        this._closing = null;
        this._confirmClose = null;
        this._moreFor = null;
        this._mergeAnyway = null;
        this._panel = 'thread';
        this._ignoring = null;
        this._unfolded = new Set();
        this._filter = filter ?? { text: '', tags: new Set() };
        if (!filter) loadFilter().then(f => { if (!filter) { filter = f; this._filter = f; } });
        this._onKey = e => this._key(e);
    }

    connectedCallback() {
        super.connectedCallback();
        this._load(false);
        this.addEventListener('keydown', this._onKey);
        // A run started or ended (a refresh fetched GitHub), or an agent moved on (agents): the flows move on.
        this._offChange = elf.onChange(() => { if (this._error && !this._board) this._load(false); else this._softReload(); });
    }

    disconnectedCallback() {
        clearTimeout(this._timer);
        clearTimeout(this._soft);
        this._soft = null;
        this.removeEventListener('keydown', this._onKey);
        this._offChange?.();
        super.disconnectedCallback();
    }

    /**
     * The board as the elf last wrote it. {@code refresh}: the elf's refresh runs first (it fetches GitHub in the
     * background; the board is read again when it ends).
     */
    async _load(refresh) {
        if (refresh) this._starting = true;
        else if (!this._board) this._loading = true;
        this._error = null;
        clearTimeout(this._timer);
        try {
            this._state = refresh ? await elf.run('refresh') : await elf.state();
            this._setBoard(await board());
        } catch (e) {
            this._error = `Couldn't ${refresh ? 'refresh' : 'load'} the board: ${errorMessage(e)}`;
        } finally {
            this._loading = false;
            this._starting = false;
            const minutes = this._board?.refreshMinutes ?? 5;
            this._timer = setTimeout(() => this._tick(), minutes * 60_000);
        }
    }

    /**
     * While the tab is open, the board is fetched as often as its settings say (as the desk's was), unless the elf's
     * own schedule fetched it since.
     */
    _tick() {
        const minutes = this._board?.refreshMinutes ?? 5;
        const fetched = this._board?.fetchedAt ? new Date(this._board.fetchedAt).getTime() : 0;
        this._load(!document.hidden && !this._refreshing && Date.now() - fetched >= minutes * 60_000);
    }

    /** A refresh is running: the button waits for it. */
    get _refreshing() {
        return this._starting || !!this._state?.running?.refresh;
    }

    /**
     * Read the board's file again (no GitHub calls), at most once a second. Not put off by the next change: while an
     * agent works, its report changes every half second.
     */
    _softReload() {
        if (this._soft) return;
        this._soft = setTimeout(() => {
            this._soft = null;
            this._reloadNow();
        }, 1000);
    }

    /** Right after one of your clicks: the row shows its new state at once. */
    async _reloadNow() {
        try {
            this._state = await elf.state();
            this._setBoard(await board());
        } catch (e) {
            // keep what we have
        }
    }

    _setBoard(b) {
        rememberLinks(b?.linkedPrs);
        this._board = b;
        setLastBoard(b);
    }

    // ---- actions ----

    /** Runs a row's step. Agent steps leave the row showing the agent at work; nothing navigates. */
    async _run(item, next) {
        const call = CALLS[next.id];
        if (!call) {
            // The board came from a newer desk than this page: its steps include one this page doesn't know.
            this._rowError = { key: item.key, text: `${next.label}: the GitHub elf was updated since this tab loaded. Reload the page, then try again.` };
            return;
        }
        this._doing = `${item.key} ${next.id}`;
        this._error = null;
        this._rowError = null;
        try {
            const r = await call(item.key);
            if (next.id === 'ready' && r) rememberItem(r);
            this._moreFor = null;
            await this._reloadNow();
        } catch (e) {
            // On the row, where you clicked: the top of the board may be far out of view. Cancel in the desk's bar
            // sent nothing, so there's nothing to say.
            if (!cancelled(e)) this._rowError = { key: item.key, text: `${next.label}: ${errorMessage(e)}` };
        } finally {
            this._doing = null;
        }
    }

    async _ignore(item, ignore) {
        this._ignoring = item.key;
        this._error = null;
        try {
            await act(ignore ? 'ignore' : 'unignore', { itemKey: item.key });
            this._moreFor = null;
            if (this._open === item.key) this._open = null;
            this._setBoard(await board());
        } catch (e) {
            this._error = `Couldn't ${ignore ? 'ignore' : 'stop ignoring'} ${item.key}: ${errorMessage(e)}`;
        } finally {
            this._ignoring = null;
        }
    }

    async _close(item) {
        this._closing = item.key;
        this._error = null;
        try {
            await act('close', { itemKey: item.key });
            this._confirmClose = null;
            if (this._open === item.key) this._open = null;
            this._setBoard(await board());
        } catch (e) {
            if (!cancelled(e)) this._error = `Couldn't close ${item.key}: ${errorMessage(e)}`;
        } finally {
            this._closing = null;
        }
    }

    async _watch(name) {
        this._watching = name;
        try {
            await act('watch', { name });
            await this._load(false);
        } catch (e) {
            this._error = `Couldn't watch ${name}: ${errorMessage(e)}`;
        } finally {
            this._watching = null;
        }
    }

    /** Opens a row's panel (the agent's thread unless said otherwise), or closes it when it's already showing. */
    _toggle(key, panel = 'thread') {
        const same = this._open === key && this._panel === panel;
        this._open = same ? null : key;
        this._panel = panel;
        this._moreFor = null;
    }

    _jump(id) {
        if (SECTIONS.find(s => s.id === id)?.fold) this._unfolded = new Set([...this._unfolded, id]);
        this.updateComplete.then(() => this.renderRoot.querySelector(`#sec-${id}`)?.scrollIntoView({
            behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth', block: 'start',
        }));
    }

    /** j/k: next or previous row; Enter: the row's main step; o: open or close the row. Not while typing. */
    _key(e) {
        const origin = e.composedPath()[0];
        if (e.ctrlKey || e.metaKey || e.altKey || origin?.closest?.('input, textarea, select, [contenteditable]')
                || ['INPUT', 'TEXTAREA', 'SELECT'].includes(origin?.tagName)) return;
        if (e.key === '/') {
            e.preventDefault();
            this.renderRoot.querySelector('#board-filter')?.focus();
            return;
        }
        const rows = [...this.renderRoot.querySelectorAll('li.row')].filter(r => r.offsetParent !== null);
        if (!rows.length) return;
        const at = rows.indexOf(this.renderRoot.activeElement?.closest?.('li.row') ?? null);
        if (e.key === 'j' || e.key === 'k') {
            e.preventDefault();
            const to = e.key === 'j' ? Math.min(rows.length - 1, at + 1) : Math.max(0, at < 0 ? 0 : at - 1);
            rows[to].focus();
            rows[to].scrollIntoView({ block: 'nearest' });
        } else if (at >= 0 && origin === rows[at] && e.key === 'Enter') {
            e.preventDefault();
            rows[at].querySelector(':scope > .acts .primary')?.click();
        } else if (at >= 0 && e.key === 'o') {
            e.preventDefault();
            this._toggle(rows[at].dataset.key);
        }
    }

    // ---- rendering ----

    render() {
        const b = this._board;
        if (!b && this._loading) return this._renderSkeleton();
        return html`
            ${this._error ? html`<p class="error" role="alert" style="margin-bottom:20px">${this._error}</p>` : ''}
            ${b ? this._renderBoard(b) : this._loading ? '' : this._renderNoBoard()}
        `;
    }

    /** Before the elf's first refresh there's no board file yet. */
    _renderNoBoard() {
        return html`
            <div class="panel notice">
                <p>${this._refreshing ? html`Fetching your board from GitHub for the first time.`
                    : "There's no board yet: the GitHub elf hasn't fetched GitHub."}</p>
                ${this._failed() ? html`<p class="error">The last refresh failed: ${this._failed()}</p>` : ''}
                <p><button class="primary" ?disabled=${this._refreshing} @click=${() => this._load(true)}>
                    ${this._refreshing ? spinner : ''}${this._refreshing ? 'Refreshing' : 'Refresh'}</button></p>
            </div>`;
    }

    /** The end of the last refresh's log, when it failed and none runs now. */
    _failed() {
        const log = this._refreshing ? null : this._state?.failed?.refresh;
        return log ? log.trim().split('\n').at(-1) : null;
    }

    _renderSkeleton() {
        const rows = n => Array.from({ length: n }, () => html`
            <div class="sk-row"><div class="skeleton" style="height:16px"></div>
                <div style="display:grid;gap:8px"><div class="skeleton sk-line" style="width:70%"></div>
                <div class="skeleton sk-line" style="width:40%"></div></div>
                <div class="skeleton" style="height:30px"></div></div>`);
        return html`
            <div class="hero" aria-busy="true" aria-label="Loading the board">
                <div class="skeleton" style="height:14px;width:160px"></div>
                <div class="skeleton" style="height:38px;width:min(36rem,100%)"></div>
            </div>
            <div class="sections">
                <div class="panel">${rows(3)}</div>
                <div class="panel">${rows(2)}</div>
            </div>
        `;
    }

    /** Every item with its flow, from the lanes. */
    _rows(b) {
        const items = b.lanes.flatMap(l => l.items);
        return items.map(item => ({ item, flow: b.flows?.[item.key] })).filter(r => r.flow);
    }

    _setFilter(f) {
        this._filter = f;
        filter = f;
        elf.store.set(FILTER, JSON.stringify({ text: f.text, tags: [...f.tags] })).catch(() => { /* not kept */ });
    }

    get _filtering() {
        return !!this._filter.text.trim() || this._filter.tags.size > 0;
    }

    /** The filter bar over the sections: words to look for, and the tags to show. */
    _renderFilter(all, shown) {
        const f = this._filter;
        const tags = Object.keys(ROLES).map(id => ({ id, n: all.filter(r => r.flow.role === id).length })).filter(t => t.n);
        const toggle = id => {
            const next = new Set(f.tags);
            next.has(id) ? next.delete(id) : next.add(id);
            this._setFilter({ ...f, tags: next });
        };
        return html`
            <div class="filter" role="search">
                <div class="field">
                    <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" aria-hidden="true">
                        <circle cx="7" cy="7" r="4.6"/><path d="M10.5 10.5 14 14"/></svg>
                    <input type="search" id="board-filter" aria-label="Filter by title, repo, number, tag or author" .value=${f.text}
                        placeholder="Filter by title, repo, #number or author" spellcheck="false" autocomplete="off"
                        @input=${e => this._setFilter({ ...f, text: e.target.value })}
                        @keydown=${e => { if (e.key === 'Escape') { e.preventDefault(); this._setFilter({ text: '', tags: new Set() }); } }}>
                    <span class="end">
                        ${this._filtering ? html`<span class="count" role="status">${shown} of ${all.length}</span>` : ''}
                        ${f.text ? html`<button class="clear" aria-label="Clear the filter text"
                                @click=${() => { this._setFilter({ ...f, text: '' }); this.renderRoot.querySelector('#board-filter')?.focus(); }}>
                                <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" aria-hidden="true">
                                    <path d="M3 3l6 6M9 3l-6 6"/></svg></button>`
                            : this._filtering ? '' : html`<kbd aria-hidden="true">/</kbd>`}
                    </span>
                </div>
                <div class="tags" role="group" aria-label="Show only">
                    ${tags.map(t => html`<button class="sm tag" aria-pressed=${f.tags.has(t.id) ? 'true' : 'false'}
                        @click=${() => toggle(t.id)}>${ROLES[t.id]} <span class="n">${t.n}</span></button>`)}
                    ${f.tags.size ? html`<button class="sm quiet" @click=${() => this._setFilter({ ...f, tags: new Set() })}>All tags</button>` : ''}
                </div>
            </div>`;
    }

    _renderBoard(b) {
        const all = this._rows(b);
        const rows = all.filter(r => matches(r.item, r.flow.role, this._filter));
        const by = Object.fromEntries(SECTIONS.map(s => [s.id, []]));
        rows.forEach(r => (by[r.flow.waiting] ?? by.watching).push(r));
        // Most recent first: what just changed is what you're likely to act on.
        Object.values(by).forEach(list => list.sort((x, y) => (y.flow.since ?? '').localeCompare(x.flow.since ?? '')));
        return html`
            <div class="page-top board-top"><div class="hero">
                <span class="date">${DATE.format(new Date())}</span>
                <h1>${b.scopeEmpty ? 'Pick what to watch, and your work shows up here.'
                    : boardHeadline(b, (id, text) => html`<button @click=${() => this._jump(id)}>${text}</button>`)}</h1>
                <div class="status">
                    ${b.runnerConnected ? '' : html`<span class="runner">Runner offline</span>`}
                    <span class="muted updated">Updated ${ago(b.fetchedAt)}</span>
                    <button class="sm" @click=${() => this._load(true)} ?disabled=${this._refreshing}>
                        ${this._refreshing ? spinner : ''}${this._refreshing ? 'Refreshing' : 'Refresh'}</button>
                    ${this._failed() ? html`<span class="chip bad" title=${this._failed()}>The last refresh failed</span>` : ''}
                </div>
            </div>${settingsToggle(this.settingsOpen, () => { this.settingsOpen = !this.settingsOpen; })}</div>
            <dialog class="settings-dialog" aria-label="GitHub settings" @close=${() => { this.settingsOpen = false; }}>
                ${this.settingsOpen ? html`
                    <div class="dialog-head"><h2>Settings</h2>
                        <button class="sm" @click=${() => this.renderRoot.querySelector('.settings-dialog').close()}>Done</button></div>
                    <gh-settings embedded @board-search-changed=${() => this._load(true)}></gh-settings>
                    ${this._renderElfSettings(b)}` : ''}
            </dialog>

            ${b.scopeEmpty ? html`
                <div class="panel notice">
                    <p>The board shows open PRs and issues from the orgs and repos you choose.</p>
                    <p><button class="primary" @click=${() => { this.settingsOpen = true; }}>Choose what to watch</button></p>
                </div>` : ''}
            ${b.truncated ? html`
                <div class="panel notice"><p>GitHub returned its limit of 1000 results, so some older items are missing.</p></div>` : ''}

            <div class="layout">
                <aside aria-label="Sections">
                    ${SECTIONS.map(s => html`
                        <button class="${s.id} ${by[s.id].length ? '' : 'zero'}" @click=${() => this._jump(s.id)}>
                            <span>${s.title}</span><span class="n">${by[s.id].length}</span></button>`)}
                    ${b.hidden.length || b.ignored.length ? html`<hr>` : ''}
                    ${b.hidden.length ? html`
                        <button class="zero" @click=${() => this._jump('hidden')}>
                            <span>Not watched</span><span class="n">${b.hidden.length}</span></button>` : ''}
                    ${b.ignored.length ? html`
                        <button class="zero" @click=${() => this._jump('ignored')}>
                            <span>Ignored</span><span class="n">${b.ignored.length}</span></button>` : ''}
                    <p class="keys"><kbd>j</kbd> <kbd>k</kbd> move, <kbd>Enter</kbd> next step, <kbd>o</kbd> open, <kbd>/</kbd> filter</p>
                </aside>
                <div class="sections">
                    ${b.scopeEmpty ? '' : this._renderFilter(all, rows.length)}
                    ${SECTIONS.map(s => this._renderSection(s, by[s.id]))}
                    ${this._renderHidden(b.hidden.filter(i => matches(i, null, this._filter)))}
                    ${this._renderIgnored(b.ignored.filter(i => matches(i, null, this._filter)))}
                </div>
            </div>
        `;
    }

    /**
     * The elf itself: the GitHub account it works as, and its own settings in the desk's dialog (its version, an
     * update, when it refreshes on its own, removing it), so secrets never reach the tab. The runner's settings are at
     * the bottom of the desk's + page.
     */
    updated(changed) {
        super.updated?.(changed);
        if (!changed.has('settingsOpen')) return;
        const d = this.renderRoot.querySelector('.settings-dialog');
        if (!d) return;
        if (this.settingsOpen && !d.open) {
            d.showModal();
            // The frame is as tall as the page under it: keep room for the whole dialog while it's open.
            requestAnimationFrame(() => { document.body.style.minHeight = `${d.scrollHeight + 48}px`; });
        } else if (!this.settingsOpen && d.open) {
            d.close();
        }
        if (!this.settingsOpen) document.body.style.minHeight = '';
    }

    _renderElfSettings(b) {
        return html`<section class="elf-settings" aria-labelledby="account">
            <div class="sec-head"><h2 id="account">This elf</h2></div>
            <div class="panel general">
                <span>The board works as <b>${b.login}</b>, with that gh account's token.</span>
                <button class="sm" @click=${() => { this.settingsOpen = false; elf.settings(['GITHUB_ACCOUNT'], 'GitHub account'); }}>Change</button>
            </div>
            <div class="panel general">
                <span>Its version and updates, when it refreshes on its own, and removing it.</span>
                <button class="sm" @click=${() => { this.settingsOpen = false; elf.settings(null, 'GitHub settings'); }}>Version and updates</button>
            </div>
            <p class="small muted" style="margin:0">How
                the agents run (the runner's settings) is at the bottom of the + page.</p>
        </section>`;
    }

    _renderSection(s, rows) {
        if (!rows.length && (s.id !== 'you' || this._filtering)) return '';
        const list = html`<div class="panel">${rows.length
            ? html`<ul>${rows.map(r => this._renderRow(r.item, r.flow))}</ul>`
            : html`<p class="empty">${s.empty}</p>`}</div>`;
        if (s.fold) {
            // While filtering, what matched shows: folded sections open.
            const open = this._unfolded.has(s.id) || this._filtering;
            return html`
                <section id="sec-${s.id}" class=${s.id} aria-label=${s.title}>
                    <details ?open=${open} @toggle=${e => { if (!this._filtering) this._fold(s.id, e.target.open); }}>
                        <summary>${s.title} <span class="n">${rows.length}</span></summary>
                        ${open ? list : ''}
                    </details>
                </section>`;
        }
        return html`
            <section id="sec-${s.id}" class=${s.id} aria-labelledby="h-${s.id}">
                <div class="sec-head"><h2 id="h-${s.id}">${s.title}</h2><span class="n">${rows.length}</span></div>
                ${list}
            </section>`;
    }

    _fold(id, open) {
        const s = new Set(this._unfolded);
        if (open) s.add(id); else s.delete(id);
        this._unfolded = s;
    }

    /** Who opened it, when that isn't you. */
    _authorTag(item) {
        const me = this._board?.login?.toLowerCase();
        if (!item.author || item.author.toLowerCase() === me) return '';
        return html`<a class="author" href="https://github.com/${item.author.replace(/\[bot\]$/, '')}" target="_blank"
            rel="noopener" title="Opened by ${item.author}">@${item.author}</a>`;
    }

    _renderRow(item, f) {
        rememberItem(item);
        const open = this._open === item.key;
        const back = this._board?.returned?.[item.key];
        return html`
            <li class="row ${open ? 'open' : ''}" tabindex="0" data-key=${item.key}>
                ${kindIcon(item)}
                <div class="main" @click=${e => { if (!e.target.closest('a, button')) this._toggle(item.key); }}>
                    <div class="line1">
                        <span class="role">${roleTag(item, f.role)}</span>
                        <a class="title" href=${item.url} target="_blank" rel="noopener">${item.title}</a>
                        ${this._authorTag(item)}
                    </div>
                    <div class="meta">
                        <span>${item.repo} #${item.number}</span>
                        ${this._stepper(f)}
                        <span class="say">${f.say}</span>
                        ${f.since ? html`<span>${ago(f.since)}</span>` : ''}
                        ${back ? html`<span class="chip run" title="It was ignored; this brought it back">Back: ${back.toLowerCase()}</span>` : ''}
                        ${item.kind === 'ISSUE' && f.role !== 'issue' ? linkedPrChips(item.key) : ''}
                        ${this._commitsChip(item, f)}
                    </div>
                    ${this._rowError?.key === item.key ? html`<p class="error row-error" role="alert">${this._rowError.text}
                        <button class="quiet sm" @click=${() => { this._rowError = null; }}>Dismiss</button></p>` : ''}
                </div>
                <div class="acts">${this._actions(item, f)}</div>
                ${open ? html`<div class="inline">${this._inline(item)}</div>` : ''}
            </li>
        `;
    }

    _inline(item) {
        switch (this._panel) {
            case 'review':
                return html`<pr-review .itemKey=${item.key} @review-closed=${() => { this._open = null; }}
                    @agent-started=${() => { this._panel = 'thread'; this._reloadNow(); }}></pr-review>`;
            case 'activity':
                return html`<pr-activity bare autoOpen .itemKey=${item.key} @activity-closed=${() => { this._open = null; }}></pr-activity>`;
            default:
                return html`<gh-thread compact .itemKey=${item.key} .steps=${this._panelSteps(item)}
                    @run-step=${e => this._run(item, e.detail)}
                    @thread-posted=${() => { this._open = null; this._reloadNow(); }}></gh-thread>`;
        }
    }

    /** The row's agent and CI steps, offered inside its open panel: after reading an answer, the fix is one click. */
    _panelSteps(item) {
        const f = this._board?.flows?.[item.key];
        if (!f) return [];
        return [f.next, ...f.more].filter(n => n && PANEL_STEPS.has(n.id));
    }

    /** "▬▬▭▭ CI": how far along the flow is, and the stage it's at. */
    _stepper(f) {
        if (!f.steps?.length) return '';
        return html`<span class="steps" title=${f.steps.join(' → ')}>
            <span class="seg" aria-hidden="true">${f.steps.map((_, i) => html`<i class=${i < f.step ? 'done' : i === f.step ? 'now' : ''}></i>`)}</span>
            <span class="stage">${f.steps[f.step]}</span></span>`;
    }

    /** The main step, then More: the other steps that fit, plus Open page, Ignore and Close. */
    _actions(item, f) {
        if (this._confirmClose === item.key) return this._closeButton(item);
        if (this._mergeAnyway === item.key) return this._mergeAnywayControl(item);
        const main = f.next ? this._action(item, f.next, true) : '';
        if (this._moreFor !== item.key) {
            return html`${main}<button class="sm quiet" aria-expanded="false" aria-label="More actions"
                @click=${() => { this._moreFor = item.key; }}>⋯</button>`;
        }
        const ids = new Set([f.next?.id, ...f.more.map(n => n.id)]);
        const merged = f.waiting === 'done';
        const me = this._board.login?.toLowerCase();
        const assigned = (item.assignees ?? []).some(a => a.toLowerCase() === me);
        return html`
            ${main}
            ${f.more.map(n => this._action(item, n, false))}
            ${!merged && item.kind === 'PR' && ciFailing(item) ? html`<ci-menu .itemKey=${item.key} @ci-changed=${() => this._reloadNow()}></ci-menu>` : ''}
            ${!merged && item.kind === 'PR' && hasConflicts(item) && !ids.has('conflicts')
                ? html`<conflict-menu .itemKey=${item.key}></conflict-menu>` : ''}
            ${!merged && mergeableAnyway(item) && !ids.has('merge-anyway') && !this._board.merges?.[item.key]
                ? this._action(item, { id: 'merge-anyway', label: 'Merge without approval' }, false) : ''}
            ${!merged && item.kind === 'PR' && !ids.has('whats-new') ? this._action(item, { id: 'whats-new', label: "What's new" }, false) : ''}
            ${f.role === 'involved' && !assigned ? this._action(item, { id: 'assign', label: 'Assign me' }, false) : ''}
            <a class="btn sm quiet" href=${threadHref(item.key)}>Open page</a>
            ${!merged && !ids.has('ignore') ? html`<button class="sm quiet" ?disabled=${this._ignoring !== null} @click=${() => this._ignore(item, true)}
                title="Someone else is handling it: move it to Ignored">${this._ignoring === item.key ? spinner : ''}Ignore</button>` : ''}
            ${!merged && !ids.has('close') ? this._closeButton(item) : ''}
            <button class="sm quiet" aria-expanded="true" aria-label="Fewer actions" @click=${() => { this._moreFor = null; }}>×</button>`;
    }

    /** One step as a button (or the control that runs it: merge, update branch). */
    _action(item, n, primary) {
        const cls = primary ? 'sm primary' : 'sm quiet';
        const busy = this._doing === `${item.key} ${n.id}`;
        switch (n.id) {
            case 'open': {
                const open = this._open === item.key && this._panel === 'thread';
                return html`<button class=${cls} aria-expanded=${open ? 'true' : 'false'} @click=${() => this._toggle(item.key)}>
                    ${open ? 'Close' : n.label}</button>`;
            }
            case 'go':
                return html`<a class="btn ${cls}" href=${threadHref(n.target)}>${n.label}</a>`;
            case 'merge':
                return this._mergeControl(item);
            case 'merge-anyway':
                return primary ? html`<merge-control anyway .itemKey=${item.key} .status=${null}
                        @merge-changed=${() => this._reloadNow()}></merge-control>`
                    : html`<button class=${cls} @click=${() => { this._mergeAnyway = item.key; this._moreFor = null; }}>${n.label}</button>`;
            case 'update-branch':
                return html`<conflict-menu behind .itemKey=${item.key} @ci-changed=${() => this._reloadNow()}></conflict-menu>`;
            case 'whats-new':
                return this._panelButton(item, 'activity', n.label, cls);
            case 'read-review':
                return this._panelButton(item, 'review', n.label, cls);
            case 'close':
                return html`<button class=${cls} @click=${() => { this._confirmClose = item.key; }}>${n.label}</button>`;
            case 'ignore':
                return html`<button class=${cls} ?disabled=${this._ignoring !== null} @click=${() => this._ignore(item, true)}>
                    ${this._ignoring === item.key ? spinner : ''}${n.label}</button>`;
            default:
                return html`<button class=${cls} ?disabled=${this._doing !== null}
                    @click=${() => this._run(item, n)}>${busy ? spinner : ''}${n.label}</button>`;
        }
    }

    /**
     * How many commits a PR has. With more than one, on your PR or where you can write, a click squashes it into one
     * on its base (the runner does it; nothing reaches GitHub) and opens the row on the push to confirm.
     */
    _commitsChip(item, f) {
        const n = item.commitCount;
        if (item.kind !== 'PR' || n == null || f.waiting === 'done') return '';
        const label = `${n} commit${n === 1 ? '' : 's'}`;
        const squashable = n > 1 && !isDependabot(item) && f.waiting !== 'agent' && (f.role === 'mine' || item.canWrite);
        if (!squashable) return html`<span class="chip commits">${label}</span>`;
        const busy = this._doing === `${item.key} squash`;
        return html`<button class="chip commits squash" ?disabled=${this._doing !== null}
                title="Squash into one commit on its base. You confirm the push." @click=${() => this._squash(item)}>
            ${busy ? spinner : ''}${label}<span aria-hidden="true">Squash to 1</span></button>`;
    }

    async _squash(item) {
        this._doing = `${item.key} squash`;
        this._rowError = null;
        try {
            await squash(item.key);
            // The squashed commit waits in the row's thread as a push for you to read and confirm.
            this._open = item.key;
            this._panel = 'thread';
            this._moreFor = null;
            await this._reloadNow();
        } catch (e) {
            if (!cancelled(e)) this._rowError = { key: item.key, text: `Squash: ${errorMessage(e)}` };
        } finally {
            this._doing = null;
        }
    }

    _panelButton(item, panel, label, cls) {
        const open = this._open === item.key && this._panel === panel;
        return html`<button class=${cls} aria-expanded=${open ? 'true' : 'false'} @click=${() => this._toggle(item.key, panel)}>
            ${open ? 'Close' : label}</button>`;
    }

    /** Two clicks to close: the button, then the inline confirmation. */
    _closeButton(item) {
        const what = item.kind === 'PR' ? 'PR' : 'issue';
        if (this._confirmClose === item.key) {
            return html`<span class="confirm-close" role="group" aria-label="Confirm close">
                Close this ${what} on GitHub?
                <button class="sm danger" ?disabled=${this._closing !== null} @click=${() => this._close(item)}>
                    ${this._closing === item.key ? spinner : ''}Close ${what}</button>
                <button class="sm quiet" ?disabled=${this._closing !== null} @click=${() => { this._confirmClose = null; }}>Keep open</button>
            </span>`;
        }
        return html`<button class="sm quiet" @click=${() => { this._confirmClose = item.key; }}>Close</button>`;
    }

    _mergeAnywayControl(item) {
        return html`<merge-control anyway autoOpen .itemKey=${item.key} .status=${null}
            @merge-changed=${() => { this._mergeAnyway = null; this._reloadNow(); }}
            @merge-dismissed=${() => { this._mergeAnyway = null; }}></merge-control>`;
    }

    _mergeControl(item) {
        return html`<merge-control .itemKey=${item.key} .status=${this._board.merges?.[item.key] ?? null}
            @merge-changed=${() => this._reloadNow()}></merge-control>`;
    }

    /** Ignored and not-watched items: plain rows, folded at the bottom. */
    _renderItem(item, chips, actions = '') {
        rememberItem(item);
        return html`
            <li>
                ${kindIcon(item)}
                <div class="main" style="cursor:auto">
                    <a class="title" href=${item.url} target="_blank" rel="noopener">${item.title}</a>
                    <div class="meta">
                        <span>${item.repo} #${item.number}</span>
                        ${item.author ? html`<span>by ${item.author}</span>` : ''}
                        <span>updated ${ago(item.updatedAt)}</span>
                        ${chips.length ? html`<span class="chips">${chips}</span>` : ''}
                    </div>
                </div>
                <div class="acts">${actions}</div>
            </li>
        `;
    }

    _renderIgnored(ignored) {
        if (!ignored.length) return '';
        return html`
            <section id="sec-ignored" aria-label="Ignored">
                <details ?open=${this._ignoredOpen} @toggle=${e => { this._ignoredOpen = e.target.open; }}>
                    <summary>${count(ignored.length, 'ignored item')}, handled by someone else</summary>
                    <div class="panel">
                        <ul>${ignored.map(item => this._renderItem(item, itemChips(item), html`
                            <button class="sm" ?disabled=${this._ignoring !== null} @click=${() => this._ignore(item, false)}>
                                ${this._ignoring === item.key ? spinner : ''}Stop ignoring</button>`))}
                        </ul>
                    </div>
                </details>
            </section>
        `;
    }

    _renderHidden(hidden) {
        if (hidden.length === 0) return '';
        return html`
            <section id="sec-hidden" aria-label="Not watched">
                <details>
                    <summary>${count(hidden.length, 'more item')} from repos you don't watch</summary>
                    <div class="panel">
                        <ul>${hidden.map(item => {
                            const owner = item.repo.split('/')[0];
                            return this._renderItem(item, itemChips(item), html`
                                <button class="sm" ?disabled=${this._watching !== null} @click=${() => this._watch(item.repo)}>
                                    ${this._watching === item.repo ? spinner : ''}Watch repo</button>
                                <button class="sm quiet" ?disabled=${this._watching !== null} @click=${() => this._watch(owner)}>
                                    ${this._watching === owner ? spinner : ''}Watch ${owner}</button>`);
                        })}</ul>
                    </div>
                </details>
            </section>
        `;
    }
}

customElements.define('gh-board', GhBoard);
