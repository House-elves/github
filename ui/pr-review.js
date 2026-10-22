// The desk's "Read the review" (pr-review.js), with its calls as the GitHub elf's actions.
import { LitElement, html, css, shared, spinner, ago, markdown, markdownStyles, Ticking } from '/elf-kit/1/kit.js';
import { act, errorMessage, startAgent } from './api.js';

/** Code lines shown above a thread: the end of GitHub's hunk, where the comment sits. */
const HUNK_LINES = 8;

const STATES = { CHANGES_REQUESTED: 'asked for changes', APPROVED: 'approved', COMMENTED: 'reviewed', COMMENT: 'commented' };

/**
 * "Read the review" on your PR: what reviewers said, in full. Open threads first, each with the code it's on and
 * every reply, then the review summaries and comments; resolved threads fold away. From here the agent can
 * address it all (its commits come back as a push you check).
 */
class PrReview extends LitElement {

    static properties = {
        itemKey: { type: String },
        _reviews: { state: true },
        _busy: { state: true },
        _error: { state: true },
        _showResolved: { state: true },
    };

    static styles = [shared, markdownStyles, css`
        :host { display: block; }
        .box {
            display: grid; gap: 16px; padding: 14px 16px; border: 1.5px solid var(--accent); border-radius: var(--r-panel);
            background: var(--surface); font-size: 14px; color: var(--ink);
        }
        h3 { font-size: 15px; font-weight: 600; }
        h4 { margin: 0; font-size: 13px; font-weight: 600; color: var(--muted); }
        section { display: grid; gap: 10px; }
        .thread { display: grid; gap: 8px; border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; min-width: 0; }
        .thread.resolved { opacity: 0.75; }
        .where { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; font: 13px var(--font-code); }
        .where a { overflow-wrap: anywhere; }
        pre.hunk {
            margin: 0; font: 12.5px/1.5 var(--font-code); background: var(--surface-2); border-radius: 8px;
            padding: 6px 0; overflow-x: auto;
        }
        pre.hunk span { display: block; padding: 0 10px; white-space: pre; }
        pre.hunk .add { background: var(--ok-soft); }
        pre.hunk .del { background: var(--bad-soft); }
        pre.hunk .at { background: var(--accent-soft); }
        .said { display: grid; gap: 4px; min-width: 0; }
        .said + .said { border-top: 1px solid var(--line); padding-top: 8px; }
        .who { font-size: 12.5px; color: var(--muted); display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
        .who b { color: var(--ink); font-weight: 600; }
        .chip { font-size: 11.5px; padding: 3px 7px; }
        .acts { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
        .note { color: var(--muted); font-size: 13px; margin: 0; }
        .md { font-size: 14px; }
        .md pre { max-height: 16rem; overflow: auto; }
    `];

    constructor() {
        super();
        new Ticking(this);
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
    }

    async _load() {
        this._busy = 'load';
        this._error = null;
        try {
            this._reviews = await act('pr-reviews', { itemKey: this.itemKey });
        } catch (e) {
            this._error = errorMessage(e);
        } finally {
            this._busy = null;
        }
    }

    async _address() {
        this._busy = 'address';
        this._error = null;
        try {
            await startAgent('address-review', { itemKey: this.itemKey });
            this.dispatchEvent(new CustomEvent('agent-started', { bubbles: true, composed: true }));
        } catch (e) {
            this._error = errorMessage(e);
        } finally {
            this._busy = null;
        }
    }

    _close() {
        this.dispatchEvent(new CustomEvent('review-closed', { bubbles: true, composed: true }));
    }

    render() {
        const r = this._reviews;
        const open = r ? r.threads.filter(t => !t.resolved) : [];
        const resolved = r ? r.threads.filter(t => t.resolved) : [];
        const said = r ? r.said.filter(s => s.who.toLowerCase() !== r.you?.toLowerCase()) : [];
        return html`
            <div class="box" role="region" aria-label="The review">
                ${this._busy === 'load' ? html`<p class="note">${spinner} Reading the review…</p>` : ''}
                ${r ? html`
                    <h3>${this._headline(open, said)}</h3>
                    ${open.length ? html`<section aria-label="Open threads">
                        <h4>Open threads</h4>
                        ${open.map(t => this._thread(t))}
                    </section>` : ''}
                    ${said.length ? html`<section aria-label="Review summaries and comments">
                        <h4>Reviews and comments</h4>
                        ${said.slice().reverse().map(s => html`<div class="said">${this._who(s)}${s.body?.trim()
                            ? html`<div class="md">${markdown(s.body)}</div>` : ''}</div>`)}
                    </section>` : ''}
                    ${resolved.length ? html`<section aria-label="Resolved threads">
                        <button class="sm quiet" aria-expanded=${this._showResolved ? 'true' : 'false'}
                            @click=${() => { this._showResolved = !this._showResolved; }}>
                            ${this._showResolved ? 'Hide' : 'Show'} ${resolved.length} resolved ${resolved.length === 1 ? 'thread' : 'threads'}</button>
                        ${this._showResolved ? resolved.map(t => this._thread(t)) : ''}
                    </section>` : ''}
                    <div class="acts">
                        ${open.length || said.some(s => s.isNew) ? html`<button class="sm primary" ?disabled=${this._busy}
                            @click=${this._address}>${this._busy === 'address' ? spinner : ''}Address the review</button>` : ''}
                        <a class="btn sm" href=${r.url + '/files'} target="_blank" rel="noopener">Open on GitHub</a>
                        <button class="sm quiet" @click=${this._close}>Close</button>
                    </div>
                    ${open.length ? html`<p class="note">Address the review: the agent works through the open threads on
                        your branch and commits. You check its commits and push them; nothing is posted to GitHub.</p>` : ''}
                ` : ''}
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
            </div>
        `;
    }

    _headline(open, said) {
        const people = [...new Set([...open.flatMap(t => t.comments), ...said].filter(s => s.isNew).map(s => s.who))];
        const threads = open.length === 1 ? '1 open thread' : `${open.length} open threads`;
        if (people.length) return `New from ${people.join(' and ')}. ${threads}.`;
        return open.length ? `Nothing new since you last replied. ${threads}.` : 'No open threads.';
    }

    _who(s) {
        return html`<span class="who"><b>${s.who}</b> ${STATES[s.state] ?? ''} ${s.url
            ? html`<a href=${s.url} target="_blank" rel="noopener">${ago(s.at)}</a>` : ago(s.at)}
            ${s.isNew ? html`<span class="chip run">New</span>` : ''}</span>`;
    }

    _thread(t) {
        const first = t.comments[0];
        return html`
            <div class="thread ${t.resolved ? 'resolved' : ''}">
                <div class="where">
                    <a href=${first?.url} target="_blank" rel="noopener">${t.path}${t.line ? `:${t.line}` : ''}</a>
                    ${t.outdated ? html`<span class="chip warn">Outdated</span>` : ''}
                    ${t.resolved ? html`<span class="chip ok">Resolved</span>` : ''}
                </div>
                ${t.diffHunk ? this._hunk(t.diffHunk) : ''}
                ${t.comments.map(c => html`<div class="said">
                    <span class="who"><b>${c.who}</b> <a href=${c.url} target="_blank" rel="noopener">${ago(c.at)}</a>
                        ${c.isNew ? html`<span class="chip run">New</span>` : ''}</span>
                    <div class="md">${markdown(c.body)}</div>
                </div>`)}
            </div>`;
    }

    /** The last lines of the hunk: the comment is on its final line. */
    _hunk(text) {
        const lines = text.split('\n').filter(l => !l.startsWith('@@')).slice(-HUNK_LINES);
        return html`<pre class="hunk">${lines.map((l, i) => html`<span class=${i === lines.length - 1 ? 'at'
            : l.startsWith('+') ? 'add' : l.startsWith('-') ? 'del' : ''}>${l}</span>`)}</pre>`;
    }
}

customElements.define('pr-review', PrReview);
