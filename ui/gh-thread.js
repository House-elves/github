// The desk's thread (desk-thread.js): the full page, a route in the tab (thread/owner/repo/123), and the compact one
// a board row opens. Its calls are the GitHub elf's actions; the thread is threads/<key>.json, and the agent's live
// lines are its report in agents/<id>.json. Posting, closing and every other GitHub write waits for your click in
// the desk's bar as well as here.
import { LitElement, html, css, shared, ago, markdown, markdownStyles, spinner, Ticking, copyText, elf } from '/elf-kit/1/kit.js';
import {
    isDependabot, linksFor, prRef, threadHref, rememberItem, knownItem, kindIcon, ciFailing, ciNeedsApproval, hasConflicts,
    behind, mergeable, mergeableAnyway, lastBoard, setLastBoard, rememberBoard, boardIgnores,
} from './shared.js';
import { act, agentReport, board, cancel, cancelled, errorMessage, post, squash, startAgent, thread } from './api.js';
import './merge-control.js';
import './ci-menu.js';
import './conflict-menu.js';
import './pr-activity.js';
import './diff-view.js';

const EVENTS = {
    COMMENT: 'Comment',
    APPROVE: 'Approve',
    REQUEST_CHANGES: 'Request changes',
};

/**
 * One issue or PR's thread: your turns and the agent's, its live output while
 * it works, and the draft it produced, which you can edit, discard or post.
 * Posting is two steps: Post… shows exactly what will be sent, then
 * "Post to GitHub" sends it as you.
 */
class GhThread extends LitElement {

    static properties = {
        itemKey: { type: String },
        compact: { type: Boolean },  // inside a board row: the latest answer, the draft and the composer only
        /** In a board row: the item's other next steps ({id, label}), offered under the answer; picked: run-step. */
        steps: { type: Array },
        _thread: { state: true },
        _loaded: { state: true },
        _lines: { state: true },
        _busy: { state: true },
        _error: { state: true },
        _editing: { state: true },
        _edit: { state: true },
        _preview: { state: true },
        _keepReviewers: { state: true }, // a push's preview: don't ask its reviewers again (unticked), by draft id
        _posted: { state: true },
        _doing: { state: true },
        _confirmClose: { state: true },
        _closed: { state: true },
        _merge: { state: true },
        _ignored: { state: true },
        _toggled: { state: true },
        _recap: { state: true },
        _about: { state: true },
        _commits: { state: true },
        _diff: { state: true },
        _diffOpen: { state: true },
        _changeOpen: { state: true },
        _rebaseAsked: { state: true },
        _previewing: { state: true },
        _report: { state: true },
        _copied: { state: true },
    };

    static styles = [shared, markdownStyles, css`
        .steps-bar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
        :host([compact]) { display: block; }
        .compact { display: grid; gap: 14px; min-width: 0; }
        .back { font-size: 14px; color: var(--muted); text-decoration: none; display: inline-flex; gap: 6px; }
        .back:hover { color: var(--ink); }
        .layout { display: grid; grid-template-columns: minmax(0, 1fr) 290px; gap: 40px; align-items: start; margin-top: 18px; }
        .convo { display: grid; gap: 22px; min-width: 0; max-width: 780px; }
        .title-block { display: grid; gap: 8px; }
        .files, .change { display: grid; gap: 10px; min-width: 0; }
        .files-toggle { justify-self: start; }
        .title-block h1 { font-size: 28px; line-height: 1.2; font-weight: 600; letter-spacing: -0.02em; }
        .title-block h1 a { color: var(--ink); text-decoration: none; }
        .title-block h1 a:hover { text-decoration: underline; }
        .ref { display: inline-flex; align-items: center; gap: 8px; color: var(--muted); font-size: 14px; }
        .aside-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
        .recap { padding: 16px 20px; display: grid; gap: 12px; font-size: 14.5px; line-height: 1.55; }
        .recap h2 { font-size: 13px; font-weight: 600; color: var(--muted); margin: 0 0 4px; }
        .recap p { margin: 0; }
        .recap ol { margin: 0; padding-left: 20px; display: grid; gap: 6px; }
        .recap li { overflow-wrap: anywhere; }
        .recap .when { color: var(--muted); font-size: 13px; }
        .turn, .live, .draft { scroll-margin-top: 84px; }

        aside { position: sticky; top: 84px; padding: 18px; display: grid; gap: 14px; }
        aside h2 { font-size: 15px; font-weight: 600; }
        aside .acts { display: grid; gap: 8px; }
        aside .acts > * { width: 100%; box-sizing: border-box; }
        aside dl { margin: 0; display: grid; grid-template-columns: auto 1fr; gap: 6px 12px; font-size: 14px; }
        aside dt { color: var(--muted); }
        aside dd { margin: 0; overflow-wrap: anywhere; }
        aside dd.links { display: grid; grid-template-columns: auto 1fr; gap: 2px 8px; }
        .runner { display: inline-flex; align-items: center; gap: 7px; }
        .runner::before { content: ""; width: 8px; height: 8px; border-radius: 50%; background: var(--idle); }
        .runner.on::before { background: var(--ok); }

        .turn { display: grid; gap: 6px; }
        .files { display: grid; gap: 10px; margin-top: 4px; }
        .files-head { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; justify-content: space-between; }
        .shots { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 200px), 1fr)); gap: 10px; }
        .shot { display: grid; gap: 4px; text-decoration: none; color: var(--muted); min-width: 0; }
        .shot img {
            width: 100%; aspect-ratio: 16 / 10; object-fit: cover; object-position: top left; border-radius: 8px;
            border: 1px solid var(--line); background: var(--surface-2);
        }
        .shot:hover img { border-color: var(--accent); }
        .shot span { overflow-wrap: anywhere; }
        .others { margin: 0; padding-left: 18px; }
        .who { font-size: 13px; color: var(--muted); }
        .who b { color: var(--ink); font-weight: 600; margin-right: 6px; }
        .turn.you { justify-items: end; }
        .turn.you .body {
            background: var(--accent-soft); color: var(--ink); border-radius: 16px 16px 4px 16px;
            padding: 10px 14px; max-width: 85%; white-space: pre-wrap; overflow-wrap: anywhere;
        }
        .turn.agent .body { padding: 18px 20px; }
        .who .toggle {
            border: 0; background: none; padding: 0 0 0 10px; font: 500 13px var(--font); color: var(--accent); cursor: pointer;
        }
        .who .toggle:hover { text-decoration: underline; }
        .turn.agent .preview {
            display: block; width: 100%; text-align: left; padding: 10px 16px; font: 14px/1.5 var(--font);
            color: var(--muted); cursor: pointer; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
        }
        .turn.agent .preview:hover { color: var(--ink); background: var(--surface-2); }
        .turn.system { justify-items: center; }
        .turn.system .body { color: var(--muted); font-size: 13px; text-align: center; max-width: 36rem; }

        .live { overflow: hidden; }
        .live-head { display: flex; flex-wrap: wrap; align-items: center; gap: 10px 14px; padding: 14px 18px; }
        .live-head .state { display: inline-flex; align-items: center; gap: 9px; font-weight: 500; color: var(--accent); }
        .live-head button { margin-left: auto; }
        .lines {
            font: 12.5px/1.6 var(--font-code); max-height: 24rem; overflow: auto; margin: 0;
            background: var(--log-bg); color: var(--log-ink); padding: 14px 18px; white-space: pre-wrap; overflow-wrap: anywhere;
        }

        .draft { border: 1.5px solid var(--accent); border-radius: var(--r-panel); background: var(--surface); overflow: hidden; }
        .draft-head {
            display: flex; flex-wrap: wrap; gap: 8px 12px; align-items: center; padding: 14px 18px;
            background: var(--accent-soft); border-bottom: 1px solid var(--line);
        }
        .draft-head b { font-size: 15px; }
        .draft-head .muted { font-size: 13px; margin-left: auto; }
        .draft-body { padding: 18px 20px; display: grid; gap: 14px; }
        .inline { display: grid; gap: 10px; }
        .inline-item { border: 1px solid var(--line); border-radius: 10px; overflow: hidden; }
        .inline-item .path {
            font: 12.5px var(--font-code); color: var(--muted); background: var(--surface-2);
            padding: 7px 12px; display: flex; gap: 8px; align-items: center;
        }
        .inline-item .path button { margin-left: auto; }
        .inline-item .md { padding: 10px 12px; }
        .draft-acts { display: flex; flex-wrap: wrap; gap: 8px; padding: 0 20px 18px; }
        .events { display: flex; flex-wrap: wrap; gap: 6px; }
        .events label {
            display: inline-flex; align-items: center; gap: 7px; font-size: 14px; padding: 6px 12px;
            border: 1px solid var(--line); border-radius: 999px; cursor: pointer;
        }
        .events label:has(input:checked) { border-color: var(--accent); background: var(--accent-soft); }
        .events input { accent-color: var(--accent); margin: 0; }
        #draft-body { min-height: 10rem; }
        #issue-then { min-height: 5rem; }
        .field { display: grid; gap: 6px; font-size: 13px; color: var(--muted); }
        .field input, .field textarea { font-size: 14px; color: var(--ink); }
        .issue-title { margin: 0; font-size: 17px; }
        .then { display: grid; gap: 6px; border-top: 1px solid var(--line); padding-top: 12px; }

        .confirm { border: 1.5px solid var(--warn); border-radius: var(--r-panel); background: var(--surface); overflow: hidden; }
        .confirm .draft-head { background: var(--warn-soft); }
        .confirm p { margin: 0; font-size: 14px; }

        .composer { display: grid; gap: 10px; }
        .composer textarea { min-height: 5rem; }
        .composer .row { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
        .composer .row .muted { font-size: 13px; }
        .start { padding: 22px; display: grid; gap: 14px; }
        .start p { margin: 0; }
        .posted { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
        .posted a { overflow-wrap: anywhere; }

        @media (max-width: 1000px) {
            .layout { grid-template-columns: minmax(0, 1fr); }
            aside { position: static; order: -1; }
        }
            .ask-again { display: inline-flex; align-items: center; gap: 8px; font-size: 14px; color: var(--ink); cursor: pointer; }
`];

    constructor() {
        super();
        new Ticking(this);
        this._lines = [];
        this._loaded = false;
    }

    connectedCallback() {
        super.connectedCallback();
        // A run ended (a refresh, an action), or an agent's report moved on (agents): the thread and its lines again.
        this._offChange = elf.onChange(() => this._changed());
    }

    disconnectedCallback() {
        clearTimeout(this._soft);
        this._soft = null;
        this._offChange?.();
        super.disconnectedCallback();
    }

    updated(changed) {
        if (changed.has('itemKey') && this.itemKey) {
            this._thread = null;
            this._loaded = false;
            this._preview = null;
            this._editing = false;
            this._confirmClose = false;
            this._closed = null;
            this._toggled = new Map();
            this._recap = false;
            this._about = null;
            this._commits = 0;
            this._diff = null;
            this._diffOpen = false;
            this._changeOpen = true; // a draft's change shows open: you read it before confirming
            this._rebaseAsked = false;
            this._previewFailed = null;
            this._report = null;
            this._copied = null;
            this._seenTurn = null;
            this._scrollPending = true;
            this._load(true);
        }
        if (this._scrollPending && this._loaded) {
            this._scrollPending = false;
            if (!this.compact) this._scrollToLatest();
        }
        if (changed.has('_thread') || changed.has('_editing')) this._autoPreview();
        if (changed.has('_lines')) {
            const box = this.renderRoot.querySelector('.lines');
            if (box) box.scrollTop = box.scrollHeight;
        }
    }

    /** Opening a thread lands on what happened last: the live job, else the latest agent answer. */
    _scrollToLatest() {
        const target = this.renderRoot.querySelector('.live') ?? [...this.renderRoot.querySelectorAll('.turn.agent, .turn.system')].pop();
        if (target) requestAnimationFrame(() => target.scrollIntoView({ block: 'start' }));
    }

    /** Reads the files again, at most twice a second: an agent's report changes as its lines come in. */
    _changed() {
        if (this._soft) return;
        this._soft = setTimeout(() => {
            this._soft = null;
            this._load();
        }, 500);
    }

    /**
     * The thread from its file, and the live lines from the agent's report. The full page reads the board too (no
     * board is open beside it): the item's CI, its merge on green and whether it's ignored are there. {@code first}:
     * the page just opened, so it also asks GitHub for the item as it is now.
     */
    async _load(first = false) {
        const key = this.itemKey;
        try {
            if (!this.compact || !lastBoard) {
                const b = await board();
                if (b) {
                    setLastBoard(b);
                    rememberBoard(b);
                }
            }
            const t = await thread(key);
            if (key !== this.itemKey) return;
            this._thread = t;
            const kind = t?.kind ?? knownItem(key)?.kind;
            if (kind === 'PR' && lastBoard?.merges?.[key] && this._merge?.state !== 'merged') this._merge = lastBoard.merges[key];
            this._ignored = boardIgnores(key);
            this._report = t?.job?.agent ? await agentReport(t.job.agent) : null;
            this._lines = this._report?.lines ?? t?.job?.lines ?? [];
            this._loaded = true;
            this._seen(t);
            if (!first) return;
            // The latest merge on green, also one that ended too long ago for the board. Doesn't hold the page up.
            if (kind === 'PR' && !this.compact) {
                act('merge-status', { itemKey: key }).then(m => { if (key === this.itemKey) this._merge = m; }, () => {});
            }
            this._item();
            // Only for "Squash into one commit"; doesn't hold the page up.
            if (kind === 'PR') act('commit-count', { itemKey: key }).then(n => { if (key === this.itemKey) this._commits = n; }, () => {});
        } catch (e) {
            this._error = `Couldn't load the thread: ${errorMessage(e)}`;
        }
    }

    /**
     * The item as it is on GitHub now: what this page offers (merge, CI, conflicts) depends on it, and what you just
     * did here (approve, push, merge) changes it. Doesn't hold the page up. Asked for when the page opens and after
     * what you do here, not on every change of the files (it rewrites the board).
     */
    _item() {
        const key = this.itemKey;
        act('item', { itemKey: key }).then(i => {
            if (i) rememberItem(i);
            this.requestUpdate();
        }, () => {});
    }

    /** Showing the thread counts its answers as seen, as the desk's thread page did: once for each new answer. */
    _seen(t) {
        const last = [...(t?.turns ?? [])].reverse().find(x => x.role === 'agent');
        if (!t || t.job || !last || this._seenTurn === last.id) return;
        this._seenTurn = last.id;
        act('seen', { itemKey: this.itemKey }).catch(() => { /* counts next time */ });
    }

    async _askRebase() {
        await this._do(async () => {
            await act('ask-dependabot-to-rebase', { itemKey: this.itemKey });
            this._rebaseAsked = true;
            return null;
        }, 'rebase');
    }

    async _ready() {
        await this._do(async () => {
            const item = await act('ready-for-review', { itemKey: this.itemKey });
            if (item) rememberItem(item);
            return null;
        }, 'ready');
    }

    /** The PR's "Files changed", fetched from GitHub the first time you open it. */
    async _toggleDiff() {
        this._diffOpen = !this._diffOpen;
        if (!this._diffOpen || (this._diff && this._diff !== 'failed')) return;
        this._diff = 'loading';
        try {
            this._diff = (await act('pr-diff', { itemKey: this.itemKey })) ?? 'failed';
        } catch (e) {
            this._diff = 'failed';
        }
    }

    _renderFiles(url) {
        const d = this._diff;
        return html`
            <section class="files" aria-label="Files changed">
                <button class="files-toggle" aria-expanded=${this._diffOpen ? 'true' : 'false'} @click=${this._toggleDiff}>
                    ${this._diffOpen ? 'Hide files changed' : 'Show files changed'}</button>
                ${!this._diffOpen ? ''
                    : d === 'loading' ? html`<div class="skeleton" style="height:120px"></div>`
                    : d === 'failed' ? html`<p class="muted small">GitHub didn't give the diff (it may be too big).
                        <a href="${url}/files" target="_blank" rel="noopener">See it on GitHub</a>.</p>`
                    : d ? html`<diff-view .diff=${d.text} .cut=${d.cut} .url=${url + '/files'}></diff-view>` : ''}
            </section>`;
    }

    /** A draft's change (commits to push, or a fix to open as a PR), open to read before confirming. */
    _renderChange(patch, cut, diffstat) {
        if (!patch) return diffstat ? html`<pre class="lines" style="max-height:12rem">${diffstat}</pre>` : '';
        return html`
            <div class="change">
                ${this._changeOpen ? html`<diff-view .diff=${patch} .cut=${cut}></diff-view>`
                    : diffstat ? html`<pre class="lines" style="max-height:12rem">${diffstat}</pre>` : ''}
                <button class="files-toggle" aria-expanded=${this._changeOpen ? 'true' : 'false'}
                        @click=${() => { this._changeOpen = !this._changeOpen; }}>
                    ${this._changeOpen ? 'Hide the change' : 'Show the change'}</button>
            </div>`;
    }

    /** The runner squashes it; the squashed commit comes back in the thread as a push you confirm. */
    _squash() {
        return this._do(() => squash(this.itemKey), 'squash');
    }

    /**
     * Runs an action that answers with the new ThreadView (or null). `what` names the control showing a spinner.
     * Cancel in the desk's bar sent nothing: there's nothing to say about it.
     */
    async _do(call, what = null) {
        this._busy = true;
        this._doing = what;
        this._error = null;
        try {
            const t = await call();
            if (t) {
                this._thread = t;
                this._report = t.job?.agent ? await agentReport(t.job.agent) : null;
                this._lines = this._report?.lines ?? t.job?.lines ?? [];
            }
            return true;
        } catch (e) {
            if (!cancelled(e)) this._error = errorMessage(e);
            return false;
        } finally {
            this._busy = false;
            this._doing = null;
        }
    }

    _spin(what) {
        return this._doing === what ? spinner : '';
    }

    // ---- actions ----

    _start(action) {
        return this._do(() => startAgent('start', { itemKey: this.itemKey, action }), action);
    }

    async _send(e) {
        e.preventDefault();
        const box = this.renderRoot.querySelector('#reply');
        const text = box.value.trim();
        if (!text) return;
        if (await this._do(() => startAgent('reply', { itemKey: this.itemKey, text }), 'send')) box.value = '';
    }

    _onReplyKey(e) {
        if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) this._send(e);
    }

    _beginEdit() {
        const d = this._thread.draft.draft ?? this._thread.draft.pr;
        this._edit = { event: d.event, body: d.body, comments: [...(d.comments ?? [])], repo: d.repo, title: d.title, then: d.then };
        this._editing = true;
        this._preview = null;
    }

    async _saveEdit() {
        const field = id => this.renderRoot.querySelector(id)?.value;
        const body = field('#draft-body');
        const kind = this._thread.draft.kind;
        const draft = kind === 'issue'
            ? { kind: 'issue', body, comments: [], repo: field('#issue-repo'), title: field('#issue-title'), then: field('#issue-then') }
            : kind === 'pr' ? { kind: 'pr', body, comments: [], title: field('#issue-title') }
            : { kind: this._thread.draft.kind, event: this._edit.event, body, comments: this._edit.comments };
        // The edited draft goes as JSON in one value: an action's values are plain.
        const draftId = this._thread.draft.id;
        if (await this._do(() => act('update-draft', { draftId, draft: JSON.stringify(draft) }), 'save')) this._editing = false;
    }

    _removeInline(i) {
        this._edit = { ...this._edit, comments: this._edit.comments.filter((_, j) => j !== i) };
    }

    /** The draft's preview (exactly what will be sent, and its one-time confirmation) is loaded. */
    _previewed() {
        const d = this._thread?.draft;
        return !!d && this._preview?.draftId === d.id;
    }

    /**
     * A draft is previewed as soon as it shows, so the send buttons sit right under it: one click posts. Not while
     * editing, and not again after a preview failed (the draft's own button retries).
     */
    async _autoPreview() {
        const d = this._thread?.draft;
        if (!d || this._editing || this._previewed() || this._previewing || this._previewFailed === d.id) return;
        this._previewing = true;
        try {
            const p = await act('preview-post', { draftId: d.id });
            if (this._thread?.draft?.id === d.id && !this._editing) this._preview = p;
        } catch (e) {
            this._previewFailed = d.id;
            this._error = errorMessage(e);
        } finally {
            this._previewing = false;
        }
    }

    async _previewPost() {
        this._busy = true;
        this._doing = 'preview';
        this._error = null;
        try {
            this._preview = await act('preview-post', { draftId: this._thread.draft.id });
        } catch (e) {
            this._error = errorMessage(e);
        } finally {
            this._busy = false;
            this._doing = null;
        }
    }

    async _toggleIgnore() {
        const ignore = !this._ignored;
        await this._do(async () => {
            await act(ignore ? 'ignore' : 'unignore', { itemKey: this.itemKey });
            this._ignored = ignore;
            return null;
        }, 'ignore');
    }

    async _close() {
        this._busy = true;
        this._doing = 'close';
        this._error = null;
        try {
            this._closed = await act('close', { itemKey: this.itemKey });
            this._confirmClose = false;
            await this._load();
        } catch (e) {
            if (!cancelled(e)) this._error = errorMessage(e);
        } finally {
            this._busy = false;
            this._doing = null;
        }
    }

    /** asDraft: a PR opens as a draft unless false (for other drafts it means nothing; buttons pass their click event). */
    async _post(asDraft) {
        asDraft = asDraft !== false;
        this._busy = true;
        this._doing = asDraft ? 'post' : 'post-ready';
        this._error = null;
        try {
            const p = this._preview;
            // kind and itemKey are for the desk's bar, so it always says what's posted where; the elf checks them.
            const askAgain = p.kind === 'push' && p.askAgain?.length && this._keepReviewers !== p.draftId ? p.askAgain.join(',') : null;
            const r = await post({ draftId: p.draftId, confirm: p.confirm, asDraft, kind: p.kind, itemKey: this.itemKey, askAgain });
            this._posted = r.url;
            this._preview = null;
            this.dispatchEvent(new CustomEvent('thread-posted', { bubbles: true, composed: true, detail: { url: r.url } }));
            // A push starts CI again: drop the old result (and its CI failing menu) until the board catches up.
            const item = knownItem(this.itemKey);
            if (r.pushedTo && item) rememberItem({ ...item, ci: 'PENDING', mergeState: 'UNKNOWN' });
            await this._load();
            this._item();
        } catch (e) {
            // Cancel in the desk's bar: nothing was sent, and the preview still holds.
            if (cancelled(e)) return;
            const msg = errorMessage(e);
            this._error = msg.includes('Preview it again')
                ? 'That preview had expired, so nothing was sent. It is refreshed now: check it and click again.' : msg;
            this._preview = null;
            this._previewFailed = null;
            this._autoPreview();
        } finally {
            this._busy = false;
            this._doing = null;
        }
    }

    // ---- rendering ----

    render() {
        if (this.compact) return this._renderCompact();
        const t = this._thread;
        const item = knownItem(this.itemKey);
        const title = t?.title ?? item?.title ?? this.itemKey;
        const url = t?.url ?? item?.url;
        const kind = t?.kind ?? item?.kind;
        return html`
            <a class="back" href="#github">← Back to GitHub</a>
            <div class="layout">
                <div class="convo">
                    <div class="title-block">
                        <span class="ref">${kind ? kindIcon({ kind, ci: item?.ci }) : ''}${this.itemKey}</span>
                        <h1>${url ? html`<a href=${url} target="_blank" rel="noopener">${title}</a>` : title}</h1>
                    </div>
                    ${this._recap && t ? this._renderRecap(t, kind, item) : ''}
                    ${kind === 'PR' && url ? this._renderFiles(url) : ''}
                    ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                    ${this._posted ? html`<p class="posted"><span class="chip ok">Posted</span>
                        <a href=${this._posted} target="_blank" rel="noopener">${this._posted}</a></p>` : ''}
                    ${!this._loaded ? this._renderSkeleton() : ''}
                    ${this._loaded && !t ? this._renderStart(kind) : ''}
                    ${t ? t.turns.map(turn => this._renderTurn(turn)) : ''}
                    ${t?.job ? this._renderJob(t) : ''}
                    ${t?.draft ? this._renderDraft(t.draft) : ''}
                    ${this._previewed() && !this._editing ? this._renderConfirm(this._preview) : ''}
                    ${this._loaded ? this._renderComposer(t) : ''}
                </div>
                ${this._loaded ? this._renderAside(t, kind, url, item) : ''}
            </div>
        `;
    }

    /**
     * Inside a board row: what the agent said last, what it's doing, the draft with its send buttons, and a box to
     * reply. The full page has the history and everything else.
     */
    _renderCompact() {
        const t = this._thread;
        const kind = t?.kind ?? knownItem(this.itemKey)?.kind;
        const url = t?.url ?? knownItem(this.itemKey)?.url;
        const turns = t?.turns ?? [];
        const lastAnswer = [...turns].reverse().find(x => x.role === 'agent');
        // What happened since that answer (the agent stopped, you re-ran CI, ...): a later note mustn't hide an
        // earlier one, such as why the agent stopped.
        const notes = turns.slice(lastAnswer ? turns.indexOf(lastAnswer) + 1 : 0).filter(x => x.role === 'system').slice(-3);
        return html`
            <div class="compact">
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                ${this._posted ? html`<p class="posted"><span class="chip ok">Posted</span>
                    <a href=${this._posted} target="_blank" rel="noopener">${this._posted}</a></p>` : ''}
                ${!this._loaded ? this._renderSkeleton() : ''}
                ${this._loaded && !t ? html`<p class="muted small" style="margin:0">No agent has looked at this yet.</p>` : ''}
                ${lastAnswer && !t?.job ? this._renderTurn(lastAnswer) : ''}
                ${!t?.job ? notes.map(n => this._renderTurn(n)) : ''}
                ${t?.job ? this._renderJob(t) : ''}
                ${kind === 'PR' && url && t?.draft?.kind === 'review' ? this._renderFiles(url) : ''}
                ${t?.draft ? this._renderDraft(t.draft) : ''}
                ${this._previewed() && !this._editing ? this._renderConfirm(this._preview) : ''}
                ${this.steps?.length && !t?.job ? html`<div class="steps-bar" role="group" aria-label="Next steps">
                    <span class="muted small">${t?.draft ? 'Or instead' : 'Next'}</span>
                    ${this.steps.map(s => html`<button class="sm" @click=${() => this.dispatchEvent(new CustomEvent('run-step',
                        { detail: s, bubbles: true, composed: true }))}>${s.label}</button>`)}
                </div>` : ''}
                ${this._loaded ? this._renderComposer(t) : ''}
                <a class="small" href=${threadHref(this.itemKey)}>Open the full page${t?.turns?.length > 1 ? ` (${t.turns.length} turns)` : ''}</a>
            </div>
        `;
    }

    _renderSkeleton() {
        return html`<div aria-busy="true" style="display:grid;gap:14px">
            <div class="skeleton" style="height:16px;width:40%"></div>
            <div class="skeleton" style="height:90px"></div>
            <div class="skeleton" style="height:16px;width:60%"></div>
        </div>`;
    }

    _renderAside(t, kind, url, item) {
        const working = !!t?.job;
        const merged = this._merge?.state === 'merged';
        return html`
            <aside class="panel" aria-label="About this item">
                <div class="aside-head">
                    <h2>${kind === 'ISSUE' ? 'Issue' : kind === 'PR' ? 'Pull request' : 'Item'}${this._ignored ? html` <span class="chip">Ignored</span>` : ''}</h2>
                    ${t?.turns?.length ? html`<button class="sm" aria-expanded=${this._recap ? 'true' : 'false'} @click=${this._toggleRecap}
                        title="What this is and what happened so far">${this._recap ? 'Hide recap' : 'Recap'}</button>` : ''}
                </div>
                <dl>
                    <dt>Repo</dt><dd>${this.itemKey.split('#')[0]}</dd>
                    ${item?.author ? html`<dt>Author</dt><dd>${item.author}</dd>` : ''}
                    ${item?.updatedAt ? html`<dt>Updated</dt><dd>${ago(item.updatedAt)}</dd>` : ''}
                    ${t?.posted?.length ? html`<dt>Posted</dt><dd>${t.posted.length} from here</dd>` : ''}
                    ${kind === 'ISSUE' && linksFor(this.itemKey).length ? html`<dt>PRs</dt><dd class="links">${linksFor(this.itemKey).map(pr => html`
                        <a href=${threadHref(pr.key)} title=${pr.title}>${prRef(this.itemKey, pr.key)}</a>
                        <span class="muted">${pr.fixes ? 'fixes it' : 'mentions it'}, by ${pr.author}</span>`)}</dd>` : ''}
                    <dt>Runner</dt><dd><span class="runner ${t?.runnerConnected ? 'on' : ''}">${t ? (t.runnerConnected ? 'Online' : 'Offline') : 'Unknown'}</span></dd>
                </dl>
                <div class="acts">
                    ${t && !working && kind === 'PR' && (t.own || !item?.approved) && !merged ? html`<button class="primary" ?disabled=${this._busy} @click=${() => this._start('review')}>
                        ${this._spin('review')}Review again</button>` : ''}
                    ${t && !working && kind === 'ISSUE' && !this._closed && t.turns.some(x => x.role === 'agent') && t.draft?.kind !== 'pr'
                        ? html`<button class="primary" ?disabled=${this._busy} @click=${() => this._start('fix')}
                            title="The agent fixes it as one commit; you review it before anything is pushed or a PR opened">
                            ${this._spin('fix')}Fix it</button>` : ''}
                    ${t && !working && kind === 'ISSUE' ? html`<button ?disabled=${this._busy} @click=${() => this._start('investigate')}>
                        ${this._spin('investigate')}Investigate again</button>` : ''}
                    ${kind === 'PR' && !this._closed ? html`
                        ${this._merge && this._merge.state !== 'waiting' && this._merge.outcome ? html`
                            <span class="chip ${this._merge.state === 'merged' ? 'merged' : this._merge.state === 'failed' ? 'bad' : ''}"
                                  style="white-space:normal;line-height:1.35">${this._merge.outcome}</span>` : ''}
                        ${this._merge?.state !== 'merged' && ((item && mergeable(item)) || this._merge?.state === 'waiting') ? html`<merge-control block .itemKey=${this.itemKey} .status=${this._merge}
                            @merge-changed=${e => { this._merge = e.detail; }}></merge-control>` : ''}` : ''}
                    ${item && ciNeedsApproval(item) && !merged ? html`<ci-menu block approval .itemKey=${this.itemKey}></ci-menu>` : ''}
                    ${item && ciFailing(item) && !merged && !working ? html`<ci-menu block .itemKey=${this.itemKey}
                        @thread-changed=${() => this._load()}></ci-menu>` : ''}
                    ${item && hasConflicts(item) && !merged && !working ? html`<conflict-menu block .itemKey=${this.itemKey} @thread-changed=${() => this._load()}></conflict-menu>` : ''}
                    ${item && behind(item) && !merged && !working ? html`<conflict-menu block behind .itemKey=${this.itemKey}></conflict-menu>` : ''}
                    ${item && mergeableAnyway(item) && !this._merge && !merged && !this._closed ? html`
                        <merge-control block anyway .itemKey=${this.itemKey} .status=${null}
                            @merge-changed=${e => { this._merge = e.detail; }}></merge-control>` : ''}
                    ${item && isDependabot(item) && !hasConflicts(item) && !behind(item) && !merged && !this._closed
                        ? (this._rebaseAsked ? html`<span class="chip run" style="justify-content:center">${spinner}Dependabot is rebasing</span>`
                            : html`<button ?disabled=${this._busy} @click=${this._askRebase}
                                title='Comment "@dependabot rebase" on the PR, as you'>${this._spin('rebase')}Ask Dependabot to rebase</button>`) : ''}
                    ${kind === 'PR' && !merged && !this._closed && !working && this._commits > 1 && !(item && isDependabot(item)) && t?.draft?.kind !== 'push'
                        ? html`<button ?disabled=${this._busy} @click=${this._squash}
                            title="Make the PR one commit on its base. You confirm the push.">
                            ${this._spin('squash')}Squash into one commit (${this._commits})</button>` : ''}
                    ${kind === 'PR' && item?.draft && !merged && !this._closed
                        && item.author?.toLowerCase() === lastBoard?.login?.toLowerCase() ? html`
                        <button class="primary" ?disabled=${this._busy} @click=${this._ready}
                            title="Your draft PR: once you've read the change on GitHub, ask for reviews">${this._spin('ready')}Ready for review</button>` : ''}
                    ${kind === 'PR' && !merged ? html`<pr-activity block .itemKey=${this.itemKey} @thread-changed=${() => this._load()}></pr-activity>` : ''}
                    ${url ? html`<a class="btn" href=${url} target="_blank" rel="noopener">Open on GitHub</a>` : ''}
                    ${!merged && !this._closed ? html`<button class="quiet" ?disabled=${this._busy} @click=${this._toggleIgnore}
                        title=${this._ignored ? 'Show it in its lane again' : 'Someone else is handling it: move it to Ignored'}>
                        ${this._spin('ignore')}${this._ignored ? 'Stop ignoring' : 'Ignore'}</button>` : ''}
                    ${merged ? ''
                        : this._closed ? html`<span class="chip ok" style="justify-content:center">Closed on GitHub</span>`
                        : this._confirmClose ? html`
                            <p class="small" style="margin:4px 0 0">Close this ${kind === 'PR' ? 'PR' : 'issue'} on GitHub?</p>
                            <button class="danger" ?disabled=${this._busy} @click=${this._close}>${this._spin('close')}Close ${kind === 'PR' ? 'PR' : 'issue'}</button>
                            <button class="quiet" ?disabled=${this._busy} @click=${() => { this._confirmClose = false; }}>Keep open</button>`
                        : html`<button class="quiet" @click=${() => { this._confirmClose = true; }}>Close ${kind === 'PR' ? 'PR' : kind === 'ISSUE' ? 'issue' : ''}</button>`}
                </div>
            </aside>
        `;
    }

    _renderStart(kind) {
        return html`
            <section class="panel start">
                <p>No agent has looked at this yet. Start one, or ask a question below.</p>
                <div class="draft-acts" style="padding:0">
                    ${kind !== 'ISSUE' && !knownItem(this.itemKey)?.approved ? html`<button class="primary" ?disabled=${this._busy} @click=${() => this._start('review')}>
                        ${this._spin('review')}Review this PR</button>` : ''}
                    ${kind !== 'PR' ? html`<button class="primary" ?disabled=${this._busy} @click=${() => this._start('investigate')}>
                        ${this._spin('investigate')}Investigate this issue</button>` : ''}
                </div>
            </section>
        `;
    }

    _renderTurn(turn) {
        if (turn.role === 'system') {
            return html`<div class="turn system"><div class="body">${turn.text}</div></div>`;
        }
        if (turn.role === 'you') {
            return html`<div class="turn you"><span class="who">${ago(turn.created)}</span><div class="body">${turn.text}</div></div>`;
        }
        const open = this._isOpen(turn);
        return html`
            <div class="turn agent ${open ? '' : 'collapsed'}">
                <span class="who"><b>Agent</b>${ago(turn.created)}
                    <button class="toggle" aria-expanded=${open ? 'true' : 'false'} @click=${() => this._toggle(turn, open)}>
                        ${open ? 'Collapse' : 'Expand'}</button></span>
                ${open
                    ? html`<div class="body panel md">${markdown(turn.text)}</div>`
                    : html`<button class="body panel preview" @click=${() => this._toggle(turn, open)}
                            title="Show the whole answer">${this._summary(turn.text)}</button>`}
                ${turn.files?.length ? this._renderAgentFiles(turn) : ''}
            </div>
        `;
    }

    /**
     * What the agent left for you with this answer: images as thumbnails (open full size), the rest by name. The tab
     * can't download (its frame has no network), so a text file is copied for you instead, and the hint says where the
     * files are.
     */
    _renderAgentFiles(turn) {
        const size = n => n < 1024 * 1024 ? `${Math.max(1, Math.round(n / 1024))} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`;
        const images = turn.files.filter(f => f.type.startsWith('image/'));
        const others = turn.files.filter(f => !f.type.startsWith('image/'));
        return html`
            <div class="files" role="group" aria-label="Files from the agent">
                <div class="files-head">
                    <span class="muted small">${turn.files.length === 1 ? '1 file' : `${turn.files.length} files`} from the agent</span>
                    <span class="muted small">They're in ~/.config/github/agents/ on this machine.</span>
                </div>
                ${images.length ? html`<div class="shots">${images.map(f => html`
                    <a class="shot" href=${elf.imageUrl('agents', f.file)} target="_blank" rel="noopener" title="Open ${f.name} full size">
                        <img src=${elf.imageUrl('agents', f.file)} alt=${f.name} loading="lazy">
                        <span class="small">${f.name}</span></a>`)}</div>` : ''}
                ${others.length ? html`<ul class="others">${others.map(f => html`
                    <li><span title=${f.file}>${f.name}</span> <span class="muted small">${size(f.size)}</span>
                        ${f.type.startsWith('text/') ? html`<button class="sm quiet" @click=${() => this._copyFile(f)}>
                            ${this._copied === f.file ? 'Copied' : 'Copy'}</button>` : ''}</li>`)}</ul>` : ''}
            </div>`;
    }

    /** A text file the agent left, copied to paste somewhere: the tab can't save files. */
    async _copyFile(f) {
        this._error = null;
        try {
            const text = (await elf.read('agents', [f.file]))[f.file];
            if (text == null) throw new Error("it isn't there any more");
            await copyText(text);
            this._copied = f.file;
        } catch (e) {
            this._error = `Couldn't copy ${f.name}: ${errorMessage(e)}`;
        }
    }

    /** The latest agent answer is open by default, older ones collapsed; your own toggles win. */
    _isOpen(turn) {
        if (this._toggled?.has(turn.id)) return this._toggled.get(turn.id);
        const agentTurns = (this._thread?.turns ?? []).filter(t => t.role === 'agent');
        return agentTurns.length === 0 || agentTurns[agentTurns.length - 1].id === turn.id;
    }

    _toggle(turn, open) {
        const next = new Map(this._toggled ?? []);
        next.set(turn.id, !open);
        this._toggled = next;
    }

    /** One line of plain text from the answer's Markdown. */
    _summary(text) {
        const plain = (text ?? '').replace(/```[\s\S]*?```/g, ' ').replace(/[#*_`>\[\]]/g, '')
            .replace(/\(https?:[^)]*\)/g, '').replace(/\s+/g, ' ').trim();
        return plain.length > 160 ? plain.slice(0, 160) + '…' : plain;
    }

    async _toggleRecap() {
        this._recap = !this._recap;
        if (!this._recap) return;
        // What the PR does or the issue is about, from its description. Doesn't hold the recap up.
        if (!this._about) {
            const key = this.itemKey;
            this._about = { loading: true };
            act('about', { itemKey: key }).then(
                a => { if (key === this.itemKey) this._about = a; },
                () => { if (key === this.itemKey) this._about = { failed: true }; });
        }
        await this.updateComplete;
        this.renderRoot.querySelector('.title-block')?.scrollIntoView({ block: 'start', behavior: 'smooth' });
    }

    /** First sentence of an answer: agents lead with what matters. */
    _lead(text) {
        const plain = (text ?? '').replace(/```[\s\S]*?```/g, ' ').replace(/[#*_`>\[\]]/g, '')
            .replace(/\(https?:[^)]*\)/g, '').replace(/\s+/g, ' ').trim();
        const first = plain.split(/(?<=[.!?])\s/)[0] ?? '';
        return first.length > 220 ? first.slice(0, 220) + '…' : first;
    }

    /** ", changing 5 files (+120 −30), to fix #123" for a PR, once its description is in. */
    _aboutSize() {
        const a = this._about;
        if (!a || a.files == null) return '';
        const size = `, changing ${a.files} ${a.files === 1 ? 'file' : 'files'} (+${a.additions} −${a.deletions})`;
        return size + (a.closes?.length ? `, to fix ${a.closes.join(', ')}` : '');
    }

    /**
     * A short recap, built from the thread itself and the item's description (no agent run): what the item is, each thing you asked with
     * the agent's conclusion, what was posted, and where it stands now.
     */
    _renderRecap(t, kind, item) {
        const turns = t.turns;
        const firstAnswer = turns.find(x => x.role === 'agent');
        const steps = [];
        turns.forEach((turn, i) => {
            if (turn.role !== 'you') return;
            const next = turns.slice(i + 1).find(x => x.role !== 'you');
            // The first answer's lead is already "What this is".
            const outcome = !next || next === firstAnswer ? null : next.role === 'agent' ? this._lead(next.text) : next.text;
            steps.push({ at: turn.created, ask: turn.text, outcome });
        });
        const posted = (t.posted ?? []).map(p => ({ at: p.at, posted: p }));
        const events = [...steps, ...posted].sort((a, b) => new Date(a.at) - new Date(b.at));
        const postedLabel = { review: 'Posted a review', comment: 'Posted a comment', issue: 'Opened an issue', pr: 'Opened a PR', push: 'Pushed commits', merge: 'Merged' };

        const now = [];
        if (t.job) now.push(t.job.state === 'queued' ? 'An agent run is waiting for the runner.' : 'The agent is working on it now.');
        if (t.draft) now.push(t.draft.kind === 'push' ? 'A commit is ready to push: confirm it below.'
            : t.draft.kind === 'issue' ? `An issue for ${t.draft.draft.repo} is drafted, waiting for you to open it.`
            : t.draft.kind === 'pr' ? 'A fix is committed and its PR drafted, waiting for you to open it.'
            : 'A draft is waiting for you to edit or post.');
        if (this._merge?.state === 'waiting') now.push('Set to merge once CI is green.');
        else if (this._merge?.state === 'merged') now.push('Merged.');
        else if (this._merge?.state === 'failed') now.push(`Merge didn't happen: ${this._merge.outcome}`);
        if (this._closed) now.push('Closed on GitHub.');
        if (this._ignored) now.push('Ignored: someone else is handling it.');
        if (item && kind === 'PR' && this._merge?.state !== 'merged') {
            if (ciFailing(item)) now.push('CI is failing.');
            else if (ciNeedsApproval(item)) now.push('CI is waiting for a maintainer to approve it.');
            else if (item.ci === 'PENDING' || item.ci === 'EXPECTED') now.push('CI is running.');
            if (hasConflicts(item)) now.push('It has merge conflicts.');
            if (item.reviewDecision === 'APPROVED') now.push('It\'s approved.');
            else if (item.reviewDecision === 'CHANGES_REQUESTED') now.push('Changes were requested.');
        }
        if (!now.length) now.push(`Nothing pending from the desk. Last activity ${ago(turns[turns.length - 1]?.created)}.`);

        return html`
            <section class="panel recap" aria-label="Recap">
                <div>
                    <h2>What this is</h2>
                    <p>${kind === 'ISSUE' ? 'Issue' : 'PR'}${item?.author ? ` by ${item.author}` : ''}${this._aboutSize()}.
                        ${this._about?.loading ? html`<span class="when">Reading the description…</span>`
                            : this._about?.summary || (this._about && !this._about.failed ? 'It has no description.' : '')}</p>
                    ${firstAnswer ? html`<p>The agent's first look: ${this._lead(firstAnswer.text)}</p>` : ''}
                </div>
                <div>
                    <h2>What happened</h2>
                    <ol>${events.map(e => e.posted
                        ? html`<li><span class="when">${ago(e.at)}</span> ${postedLabel[e.posted.kind] ?? 'Posted'}${e.posted.url
                            ? html` (<a href=${e.posted.url} target="_blank" rel="noopener">link</a>)` : ''}.</li>`
                        : html`<li><span class="when">${ago(e.at)}</span> You: ${e.ask.length > 120 ? e.ask.slice(0, 120) + '…' : e.ask}
                            ${e.outcome ? html`<br>Agent: ${e.outcome}` : ''}</li>`)}</ol>
                </div>
                <div>
                    <h2>Where it stands</h2>
                    <p>${now.join(' ')}</p>
                </div>
            </section>
        `;
    }

    _renderJob(t) {
        const j = t.job;
        // The agent's report is newer than the thread's file: it says when the runner started it.
        const started = this._report?.status === 'running' ? this._report.started : j.started;
        const waiting = j.state === 'queued' && this._report?.status !== 'running';
        const status = waiting
            ? (t.runnerConnected ? 'Sent to the runner' : 'Waiting for the runner to connect')
            : `Working, started ${ago(started)}`;
        return html`
            <section class="panel live" aria-live="polite">
                <div class="live-head">
                    <span class="state">${spinner}${status}</span>
                    <button class="sm" ?disabled=${this._busy} @click=${() => this._do(() => cancel(this.itemKey), 'stop')}>
                        ${this._spin('stop')}Stop</button>
                </div>
                ${this._lines.length ? html`<pre class="lines">${this._lines.join('\n')}</pre>` : ''}
            </section>
        `;
    }

    _renderPush(dv) {
        const p = dv.push;
        return html`
            <section class="draft" aria-label="Commits to push">
                <div class="draft-head">
                    <b>${p.squashed ? `The PR as 1 commit, replacing ${p.replaces}` : `${p.commits.length} commit${p.commits.length === 1 ? '' : 's'} to push`}</b>
                    <span class="chip">${p.headRepo ? `${p.headRepo}:${p.headRef}` : "the PR's branch"}</span>
                    <span class="muted">Not pushed yet</span>
                </div>
                <div class="draft-body">
                    <ul style="margin:0;padding-left:18px">${p.commits.map(c => html`<li><code style="font:13px var(--font-code)">${c.sha.slice(0, 7)}</code> ${c.subject}</li>`)}</ul>
                    ${this._renderChange(p.patch, p.patchCut, p.diffstat)}
                </div>
                <div class="draft-acts">
                    ${this._previewed() ? '' : html`<button class="primary" ?disabled=${this._busy || this._previewing} @click=${this._previewPost}>${this._spin('preview')}Push…</button>`}
                    <button class="quiet danger" ?disabled=${this._busy} @click=${() => this._do(() => act('discard-draft', { draftId: dv.id }), 'discard')}>
                        ${this._spin('discard')}Discard</button>
                    <span class="muted small">Ask the agent below if you want changes first.</span>
                </div>
            </section>
        `;
    }

    _renderPr(dv) {
        const p = dv.pr;
        return html`
            <section class="draft" aria-label="New PR">
                <div class="draft-head">
                    <b>New PR on ${p.repo}</b>
                    <span class="chip">1 commit on ${p.base}</span>
                    <span class="muted">Not pushed or opened yet</span>
                </div>
                <div class="draft-body">
                    <h3 class="issue-title">${p.title}</h3>
                    <div class="md">${markdown(p.body)}</div>
                    <ul style="margin:0;padding-left:18px">${p.commits.map(c => html`<li><code style="font:13px var(--font-code)">${c.sha.slice(0, 7)}</code> ${c.subject}</li>`)}</ul>
                    ${this._renderChange(p.patch, p.patchCut, p.diffstat)}
                </div>
                <div class="draft-acts">
                    ${this._previewed() ? '' : html`<button class="primary" ?disabled=${this._busy || this._previewing} @click=${this._previewPost}>${this._spin('preview')}Open PR…</button>`}
                    <button ?disabled=${this._busy} @click=${this._beginEdit}>Edit</button>
                    <button class="quiet danger" ?disabled=${this._busy} @click=${() => this._do(() => act('discard-draft', { draftId: dv.id }), 'discard')}>
                        ${this._spin('discard')}Discard</button>
                    <span class="muted small">Ask the agent below if you want changes to the code first.</span>
                </div>
            </section>
        `;
    }

    _renderEditPr() {
        return html`
            <section class="draft" aria-label="Edit PR">
                <div class="draft-head"><b>Edit PR</b></div>
                <div class="draft-body">
                    <label class="field">Title <input type="text" id="issue-title" .value=${this._edit.title ?? ''}></label>
                    <label class="field">Description <textarea id="draft-body" .value=${this._edit.body}></textarea></label>
                </div>
                <div class="draft-acts">
                    <button class="primary" ?disabled=${this._busy} @click=${this._saveEdit}>${this._spin('save')}Save</button>
                    <button class="quiet" ?disabled=${this._busy} @click=${() => { this._editing = false; }}>Cancel</button>
                </div>
            </section>
        `;
    }

    _renderDraft(dv) {
        if (dv.kind === 'push') return this._renderPush(dv);
        if (dv.kind === 'pr') return this._editing ? this._renderEditPr() : this._renderPr(dv);
        if (this._editing) return this._renderEdit(dv);
        if (dv.kind === 'issue') return this._renderIssue(dv);
        const d = dv.draft;
        const isReview = dv.kind === 'review';
        return html`
            <section class="draft" aria-label="Draft">
                <div class="draft-head">
                    <b>Draft ${isReview ? 'review' : 'comment'}</b>
                    ${isReview ? html`<span class="chip ${d.event === 'APPROVE' ? 'ok' : d.event === 'REQUEST_CHANGES' ? 'warn' : ''}">${EVENTS[d.event] ?? 'Comment'}</span>` : ''}
                    ${isReview && d.comments?.length ? html`<span class="chip">${d.comments.length} inline</span>` : ''}
                    <span class="muted">${this._thread?.own ? 'Your PR: GitHub only allows a comment review' : 'Not posted yet'}</span>
                </div>
                <div class="draft-body">
                    <div class="md">${markdown(d.body)}</div>
                    ${isReview && d.comments?.length ? html`
                        <div class="inline">${d.comments.map(c => html`
                            <div class="inline-item">
                                <span class="path">${c.path}:${c.line}</span>
                                <div class="md">${markdown(c.body)}</div>
                            </div>`)}
                        </div>` : ''}
                </div>
                <div class="draft-acts">
                    ${this._previewed() ? '' : html`<button class="primary" ?disabled=${this._busy || this._previewing} @click=${this._previewPost}>${this._spin('preview')}Post…</button>`}
                    <button ?disabled=${this._busy} @click=${this._beginEdit}>Edit</button>
                    <button class="quiet danger" ?disabled=${this._busy} @click=${() => this._do(() => act('discard-draft', { draftId: dv.id }), 'discard')}>
                        ${this._spin('discard')}Discard</button>
                </div>
            </section>
        `;
    }

    _renderIssue(dv) {
        const d = dv.draft;
        return html`
            <section class="draft" aria-label="Draft issue">
                <div class="draft-head">
                    <b>Draft issue in ${d.repo}</b>
                    <span class="muted">Not opened yet</span>
                </div>
                <div class="draft-body">
                    <h3 class="issue-title">${d.title}</h3>
                    <div class="md">${markdown(d.body)}</div>
                    ${d.then ? html`
                        <div class="then">
                            <span class="small muted">Then, a comment on ${this.itemKey} (drafted for you once the issue is open):</span>
                            <div class="md">${markdown(d.then)}</div>
                        </div>` : ''}
                </div>
                <div class="draft-acts">
                    ${this._previewed() ? '' : html`<button class="primary" ?disabled=${this._busy || this._previewing} @click=${this._previewPost}>${this._spin('preview')}Open issue…</button>`}
                    <button ?disabled=${this._busy} @click=${this._beginEdit}>Edit</button>
                    <button class="quiet danger" ?disabled=${this._busy} @click=${() => this._do(() => act('discard-draft', { draftId: dv.id }), 'discard')}>
                        ${this._spin('discard')}Discard</button>
                </div>
            </section>
        `;
    }

    _renderEditIssue() {
        return html`
            <section class="draft" aria-label="Edit draft issue">
                <div class="draft-head"><b>Edit draft issue</b></div>
                <div class="draft-body">
                    <label class="field">Repo <input type="text" id="issue-repo" .value=${this._edit.repo ?? ''} placeholder="owner/name"></label>
                    <label class="field">Title <input type="text" id="issue-title" .value=${this._edit.title ?? ''}></label>
                    <label class="field">Description <textarea id="draft-body" .value=${this._edit.body}></textarea></label>
                    <label class="field">Then, a comment on ${this.itemKey} (optional; {issue} becomes the issue's link)
                        <textarea id="issue-then" .value=${this._edit.then ?? ''}></textarea></label>
                </div>
                <div class="draft-acts">
                    <button class="primary" ?disabled=${this._busy} @click=${this._saveEdit}>${this._spin('save')}Save</button>
                    <button class="quiet" ?disabled=${this._busy} @click=${() => { this._editing = false; }}>Cancel</button>
                </div>
            </section>
        `;
    }

    _renderEdit(dv) {
        if (dv.kind === 'issue') return this._renderEditIssue();
        const isReview = dv.kind === 'review';
        return html`
            <section class="draft" aria-label="Edit draft">
                <div class="draft-head"><b>Edit draft ${isReview ? 'review' : 'comment'}</b></div>
                <div class="draft-body">
                    ${isReview ? html`
                        <div class="events" role="radiogroup" aria-label="Review type">
                            ${Object.entries(this._thread?.own ? { COMMENT: 'Comment' } : EVENTS).map(([value, label]) => html`
                                <label><input type="radio" name="event" .checked=${(this._edit.event ?? 'COMMENT') === value}
                                    @change=${() => { this._edit = { ...this._edit, event: value }; }}>${label}</label>`)}
                        </div>` : ''}
                    <textarea id="draft-body" aria-label="Draft text" .value=${this._edit.body}></textarea>
                    ${isReview && this._edit.comments.length ? html`
                        <div class="inline">${this._edit.comments.map((c, i) => html`
                            <div class="inline-item">
                                <span class="path">${c.path}:${c.line}<button class="sm quiet danger" @click=${() => this._removeInline(i)}>Remove</button></span>
                                <div class="md">${markdown(c.body)}</div>
                            </div>`)}
                        </div>` : ''}
                </div>
                <div class="draft-acts">
                    <button class="primary" ?disabled=${this._busy} @click=${this._saveEdit}>${this._spin('save')}Save</button>
                    <button class="quiet" ?disabled=${this._busy} @click=${() => { this._editing = false; }}>Cancel</button>
                </div>
            </section>
        `;
    }

    /**
     * Under a previewed draft: where it goes, what happens, and the buttons that send it. The draft above is exactly
     * what is sent, so this doesn't repeat it.
     */
    _renderConfirm(p) {
        const sending = this._doing === 'post' || this._doing === 'post-ready';
        let what, buttons;
        if (p.kind === 'push') {
            what = p.push.squashed
                ? html`This replaces the PR's branch with one commit on ${p.push.base}, under your GitHub account (a force push).
                    It only goes through if the branch is still where it was when this was made.`
                : html`This pushes to the PR's branch under your GitHub account. It only goes through if the branch hasn't
                    moved since the agent started, and it never force-pushes.`;
            const who = p.askAgain ?? [];
            const names = who.length === 1 ? who[0] : `${who.slice(0, -1).join(', ')} and ${who.at(-1)}`;
            buttons = html`<button class="primary" ?disabled=${this._busy} @click=${this._post}>${this._spin('post')}${sending ? 'Pushing' : 'Push to GitHub'}</button>
                ${who.length ? html`<label class="ask-again"><input type="checkbox" ?disabled=${this._busy}
                    .checked=${this._keepReviewers !== p.draftId}
                    @change=${e => { this._keepReviewers = e.target.checked ? null : p.draftId; }}>
                    Then ask ${names} to review again</label>` : ''}`;
        } else if (p.kind === 'pr') {
            what = html`This syncs ${p.pr.headRepo}'s ${p.pr.base} with ${p.pr.repo}, pushes the commit to a new branch
                ${p.pr.headRef} there (never a force push), and opens the PR on ${p.pr.base} under your name.`;
            buttons = html`
                <button class="primary" ?disabled=${this._busy} @click=${() => this._post(false)}>${this._spin('post-ready')}${this._doing === 'post-ready' ? 'Opening' : 'Push and open PR'}</button>
                <button ?disabled=${this._busy} @click=${() => this._post(true)}>${this._spin('post')}${this._doing === 'post' ? 'Opening' : 'Push and open as draft'}</button>`;
        } else if (p.kind === 'issue') {
            what = html`This opens the issue in ${p.draft.repo} under your name, exactly as shown.${p.draft.then
                ? ` The comment on ${this.itemKey} is drafted next, for you to post separately.` : ''}`;
            buttons = html`<button class="primary" ?disabled=${this._busy} @click=${this._post}>${this._spin('post')}${sending ? 'Opening' : 'Open issue on GitHub'}</button>`;
        } else {
            const d = p.draft;
            const inline = p.kind === 'review' && d.comments?.length
                ? `, with ${d.comments.length} inline comment${d.comments.length === 1 ? '' : 's'}` : '';
            what = html`This posts to GitHub under your name, exactly as shown${inline}.`;
            buttons = html`<button class="primary" ?disabled=${this._busy} @click=${this._post}>${this._spin('post')}${sending ? 'Posting' : 'Post to GitHub'}</button>`;
        }
        return html`
            <section class="confirm" aria-label="Send">
                <div class="draft-head"><b>${p.target}</b>${p.kind === 'review'
                    ? html`<span class="chip">${EVENTS[p.draft.event] ?? 'Comment'}</span>` : ''}</div>
                <div class="draft-body">
                    ${p.warning ? html`<p class="error" role="alert">${p.warning}</p>` : ''}
                    <p>${what}</p>
                </div>
                <div class="draft-acts">${buttons}</div>
            </section>
        `;
    }

    _renderComposer(t) {
        const working = !!t?.job;
        return html`
            <form class="composer" @submit=${this._send}>
                <textarea id="reply" aria-label="${t ? 'Reply to the agent' : 'Ask the agent'}"
                          placeholder=${t ? 'Reply to the agent' : 'Ask something about this item'}
                          @keydown=${this._onReplyKey}></textarea>
                <div class="row">
                    <button class="primary" type="submit" ?disabled=${this._busy || working}>${this._spin('send')}${t ? 'Send' : 'Ask'}</button>
                    <span class="muted">${working ? "You can reply once the agent has finished." : 'Ctrl+Enter sends'}</span>
                </div>
            </form>
        `;
    }
}

customElements.define('gh-thread', GhThread);
