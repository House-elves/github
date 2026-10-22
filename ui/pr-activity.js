// The desk's "What's new" on a PR (pr-activity.js), with its calls as the GitHub elf's actions.
import { LitElement, html, css, shared, spinner, ago, Ticking, elf } from '/elf-kit/1/kit.js';
import { onThreadPage } from './shared.js';
import { act, errorMessage, startAgent } from './api.js';

const KINDS = {
    commit: 'New commit',
    'force-push': 'Force-push',
    review: 'Review',
    comment: 'Comment',
    'review-request': 'Review request',
    ready: 'Ready for review',
};

/**
 * "What's new" on a PR: everything since your last review or comment (commits, reviews, comments, review
 * requests), with a way to have the agent review the changes. Opens from a chip ("New commits since your review")
 * or a plain button.
 */
class PrActivity extends LitElement {

    static properties = {
        itemKey: { type: String },
        /** The chip text when the board knows the PR moved on since your review; otherwise a plain button. */
        label: { type: String },
        block: { type: Boolean },
        /** Open straight away (picked from a menu). */
        autoOpen: { type: Boolean },
        /** Only the panel, no button (the board's row has its own). */
        bare: { type: Boolean },
        _open: { state: true },
        _activity: { state: true },
        _busy: { state: true },
        _error: { state: true },
    };

    static styles = [shared, css`
        :host { display: contents; }
        button.chip { border: 0; cursor: pointer; font-family: var(--font); }
        .panel-a {
            flex-basis: 100%; display: grid; gap: 10px; padding: 12px 14px; margin-top: 6px; min-width: 0;
            border: 1.5px solid var(--accent); border-radius: var(--r-panel); background: var(--surface); font-size: 14px;
            color: var(--ink); text-align: left;
        }
        ol { margin: 0; padding: 0; list-style: none; display: grid; gap: 8px; max-height: 22rem; overflow: auto; }
        li { display: grid; gap: 2px; }
        .kind { font-size: 12px; color: var(--muted); }
        .kind b { color: var(--ink); font-weight: 600; }
        .text { overflow-wrap: anywhere; }
        .acts { display: flex; flex-wrap: wrap; gap: 8px; }
        .block .acts { display: grid; }
        .note { color: var(--muted); font-size: 13px; margin: 0; }
        .error { font-size: 13px; padding: 6px 10px; }
    `];

    constructor() {
        super();
        new Ticking(this);
        this._open = false;
        this._busy = null;
    }

    firstUpdated() {
        if (this.autoOpen) this._toggle();
    }

    async _toggle() {
        this._open = !this._open;
        this._error = null;
        if (this._open) {
            this._busy = 'load';
            try {
                this._activity = await act('pr-activity', { itemKey: this.itemKey });
            } catch (e) {
                this._error = errorMessage(e);
            } finally {
                this._busy = null;
            }
        } else {
            this.dispatchEvent(new CustomEvent('activity-closed', { bubbles: true, composed: true }));
        }
    }

    async _review() {
        this._busy = 'review';
        this._error = null;
        try {
            await startAgent('start', { itemKey: this.itemKey, action: 'review' });
            // On the board: close, the row shows the agent working. On the thread page, it shows there.
            if (!onThreadPage(this.itemKey, elf.route())) this._toggle();
            else this.dispatchEvent(new CustomEvent('thread-changed', { bubbles: true, composed: true }));
        } catch (e) {
            this._error = errorMessage(e);
        } finally {
            this._busy = null;
        }
    }

    render() {
        const trigger = this.label
            ? html`<button class="chip run" aria-expanded=${this._open ? 'true' : 'false'} @click=${this._toggle}>${this.label} ▾</button>`
            : html`<button class="sm ${this.block ? '' : 'quiet'}" aria-expanded=${this._open ? 'true' : 'false'} @click=${this._toggle}>What's new</button>`;
        return html`${this.bare ? '' : trigger}${this._open ? this._renderPanel() : ''}`;
    }

    _renderPanel() {
        const a = this._activity;
        const commits = a ? a.events.filter(e => e.kind === 'commit' || e.kind === 'force-push').length : 0;
        return html`
            <div class="panel-a ${this.block ? 'block' : ''}" role="region" aria-label="What's new">
                ${this._busy === 'load' ? html`<span class="note">${spinner} Reading the PR's history…</span>` : ''}
                ${a ? html`
                    <b>${a.since ? `Since ${a.sinceWhat} ${ago(a.since)}` : "You haven't reviewed or commented yet. The latest activity:"}</b>
                    ${a.events.length === 0 ? html`<p class="note">Nothing new from anyone else.</p>` : html`
                        <ol>${a.events.slice().reverse().map(e => html`
                            <li>
                                <span class="kind"><b>${KINDS[e.kind] ?? e.kind}</b> by ${e.who || 'someone'}, ${ago(e.at)}</span>
                                <span class="text">${e.url ? html`<a href=${e.url} target="_blank" rel="noopener">${e.text}</a>` : e.text}</span>
                            </li>`)}
                        </ol>`}
                    <div class="acts">
                        ${commits ? html`<button class="sm primary" ?disabled=${this._busy} @click=${this._review}>
                            ${this._busy === 'review' ? spinner : ''}Review the changes</button>` : ''}
                        <a class="btn sm" href="https://github.com/${this.itemKey.replace('#', '/pull/')}" target="_blank" rel="noopener">Open on GitHub</a>
                        <button class="sm quiet" @click=${this._toggle}>Close</button>
                    </div>
                    ${commits ? html`<p class="note">Review the changes: the agent looks at the PR as it is now, in its thread, and you
                        decide what to post.</p>` : ''}
                ` : ''}
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
            </div>
        `;
    }
}

customElements.define('pr-activity', PrActivity);
