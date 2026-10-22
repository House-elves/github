// The desk's merge control (merge-control.js), with its calls as the GitHub elf's actions. Merging waits for your
// click in the desk's bar too: the confirmation here picks the method and the head, the bar sends it.
import { LitElement, html, css, shared, spinner } from '/elf-kit/1/kit.js';
import { act, cancelled, errorMessage } from './api.js';

const METHOD_LABELS = { merge: 'Merge commit', squash: 'Squash', rebase: 'Rebase' };
const CI_LABELS = {
    SUCCESS: { cls: 'ok', text: 'CI is green' },
    PENDING: { cls: 'run', text: 'CI is running' },
    EXPECTED: { cls: 'run', text: 'CI is about to run' },
    FAILURE: { cls: 'bad', text: 'CI is failing' },
    ERROR: { cls: 'bad', text: 'CI is failing' },
};

/**
 * Merge for one approved, green PR: the button, the inline confirmation
 * (method, the head you're agreeing to), a short "Merging" state while GitHub
 * settles, and the result. Fires `merge-changed` when the server state changes.
 */
class MergeControl extends LitElement {

    static properties = {
        itemKey: { type: String },
        /** MergeService.View from the board or thread, or null. */
        status: { type: Object },
        /** Full-width buttons, for the thread page's side panel. */
        block: { type: Boolean },
        /** "Merge without approval": CI must still be green, and GitHub refuses it if the repo requires a review. */
        anyway: { type: Boolean },
        /** Open the confirmation straight away (when picked from a menu). */
        autoOpen: { type: Boolean },
        _options: { state: true },
        _method: { state: true },
        _busy: { state: true },
        _error: { state: true },
    };

    static styles = [shared, css`
        :host { display: contents; }
        .confirm {
            display: grid; gap: 10px; padding: 12px 14px; border-radius: var(--r-panel);
            border: 1.5px solid var(--accent); background: var(--surface); font-size: 14px;
            flex-basis: 100%; text-align: left;
        }
        .methods { display: flex; flex-wrap: wrap; gap: 6px; }
        .methods label {
            display: inline-flex; align-items: center; gap: 6px; padding: 5px 11px; border-radius: 999px;
            border: 1px solid var(--line); cursor: pointer; font-size: 13px;
        }
        .methods label:has(input:checked) { border-color: var(--accent); background: var(--accent-soft); }
        .methods input { accent-color: var(--accent); margin: 0; }
        .row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
        .waiting { display: inline-flex; flex-wrap: wrap; align-items: center; gap: 8px; }
        .block { display: grid; gap: 8px; }
        .block > button { width: 100%; }
        .error { font-size: 13px; padding: 6px 10px; }
        .sha { font: 12px var(--font-code); color: var(--muted); }
    `];

    constructor() {
        super();
        this.status = null;
        this._options = null;
        this._busy = null;
    }

    firstUpdated() {
        if (this.autoOpen) this._open();
    }

    _dismiss() {
        this._options = null;
        this.dispatchEvent(new CustomEvent('merge-dismissed', { bubbles: true, composed: true }));
    }

    async _open() {
        this._busy = 'open';
        this._error = null;
        try {
            this._options = await act('merge-options', { itemKey: this.itemKey });
            this._method = this._options.defaultMethod;
        } catch (e) {
            this._error = errorMessage(e);
        } finally {
            this._busy = null;
        }
    }

    async _confirm() {
        this._busy = 'merge';
        this._error = null;
        try {
            const args = { itemKey: this.itemKey, method: this._method, headSha: this._options.headSha };
            this.status = await act(this.anyway ? 'merge-without-approval' : 'merge-on-green', args);
            this._options = null;
            this._changed();
        } catch (e) {
            if (!cancelled(e)) this._error = errorMessage(e);
        } finally {
            this._busy = null;
        }
    }

    async _cancel() {
        this._busy = 'cancel';
        this._error = null;
        try {
            this.status = await act('cancel-merge', { itemKey: this.itemKey });
            this._changed();
        } catch (e) {
            this._error = errorMessage(e);
        } finally {
            this._busy = null;
        }
    }

    _changed() {
        this.dispatchEvent(new CustomEvent('merge-changed', { bubbles: true, composed: true, detail: this.status }));
    }

    render() {
        const err = this._error ? html`<p class="error" role="alert">${this._error}</p>` : '';
        if (this.status?.state === 'waiting') {
            return html`<span class=${this.block ? 'block' : 'waiting'}>
                <span class="chip run">${spinner}Merging</span>
                <button class="sm quiet" ?disabled=${this._busy} @click=${this._cancel}>
                    ${this._busy === 'cancel' ? spinner : ''}Cancel</button>
                ${err}</span>`;
        }
        if (this._options) return html`${this._renderConfirm(this._options)}${err}`;
        const button = html`<button class="sm ${this.block ? '' : 'quiet'}" ?disabled=${this._busy} @click=${this._open}
                title=${this.anyway ? 'CI is green but nobody has approved it. Merge it yourself.' : 'Approved and green'}>
            ${this._busy === 'open' ? spinner : ''}${this.anyway ? 'Merge without approval' : 'Merge'}</button>`;
        return this.block ? html`<span class="block">${button}${err}</span>` : html`${button}${err}`;
    }

    _renderConfirm(o) {
        if (!o.open) {
            return html`<div class="confirm">This PR isn't open any more.
                <div class="row"><button class="sm quiet" @click=${() => { this._options = null; }}>Close</button></div></div>`;
        }
        if (!o.approved && !this.anyway) {
            return html`<div class="confirm">
                <span>Merging is only for approved PRs, and this one isn't approved (or changes were requested).</span>
                <div class="row"><button class="sm quiet" @click=${() => { this._options = null; }}>Close</button></div></div>`;
        }
        if (o.ci !== 'SUCCESS') {
            return html`<div class="confirm">
                <span>${o.ci === 'FAILURE' || o.ci === 'ERROR' ? 'CI is failing on this PR. Re-run or fix it from the CI menu first.'
                    : 'CI isn\'t green yet. Merge shows up once it is.'}</span>
                <div class="row"><button class="sm quiet" @click=${() => { this._options = null; }}>Close</button></div></div>`;
        }
        const ci = CI_LABELS[o.ci] ?? { cls: '', text: o.ci === null ? 'No CI checks reported' : `CI: ${o.ci}` };
        return html`
            <div class="confirm" role="group" aria-label="Merge">
                <b>Merge ${this.itemKey}${o.approved ? '' : ' without an approval'}?</b>
                <div class="row">
                    ${o.approved ? html`<span class="chip ok">Approved</span>` : html`<span class="chip warn">Not approved</span>`}
                    <span class="chip ${ci.cls}">${ci.text}</span>
                    ${o.mergeable === 'CONFLICTING' ? html`<span class="chip bad">Has conflicts</span>` : ''}
                </div>
                <span class="small muted">Merges commit <span class="sha">${o.headSha.slice(0, 7)}</span>, the one you've seen.
                    If anything new was pushed, GitHub refuses it.</span>
                ${o.methods.length > 1 ? html`
                    <div class="methods" role="radiogroup" aria-label="Merge method">
                        ${o.methods.map(m => html`<label><input type="radio" name="method-${this.itemKey}" .checked=${this._method === m}
                            @change=${() => { this._method = m; }}>${METHOD_LABELS[m] ?? m}</label>`)}
                    </div>` : html`<span class="small muted">${METHOD_LABELS[o.methods[0]] ?? o.methods[0]} (the only method this repo allows)</span>`}
                <div class="row">
                    <button class="sm primary" ?disabled=${this._busy} @click=${this._confirm}>
                        ${this._busy === 'merge' ? spinner : ''}${o.approved ? 'Merge' : 'Merge without approval'}</button>
                    <button class="sm quiet" ?disabled=${this._busy} @click=${this._dismiss}>Cancel</button>
                </div>
                ${o.approved ? '' : html`<span class="small muted">Nobody has approved it. If the repo's branch protection
                    requires a review, GitHub refuses the merge and the reason shows here.</span>`}
            </div>
        `;
    }
}

customElements.define('merge-control', MergeControl);
