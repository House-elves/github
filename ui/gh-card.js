// The GitHub elf's dashboard card: the board in one sentence, as the desk's dashboard drew it (desk-dashboard.js,
// _github), from board/board.json. Its counts link to the board; the desk draws the card's panel and title.
import { LitElement, html, shared, cardStyles, spinner, ago, Ticking, elf } from '/elf-kit/1/kit.js';
import { boardHeadline } from './shared.js';
import { board } from './api.js';

class GhCard extends LitElement {

    static properties = {
        _board: { state: true },
        _state: { state: true },
        _loaded: { state: true },
        _error: { state: true },
    };

    static styles = [shared, cardStyles];

    constructor() {
        super();
        new Ticking(this);
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
        this._off = elf.onChange(() => this._load());
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    async _load() {
        try {
            this._board = await board();
            this._state = await elf.state();
            this._error = null;
        } catch (e) {
            this._error = e?.message ?? String(e);
        }
        this._loaded = true;
        elf.card({ attention: typeof this._say() !== 'string' });
    }

    /** What the card says: a sentence with links when something waits on you, the agent or CI; else plain text. */
    _say() {
        const b = this._board;
        if (!b) return this._state?.running?.refresh ? 'Fetching your board from GitHub for the first time.'
            : 'No board yet. Refresh it from its tab.';
        // A link to # opens the tab (the kit's links): the board, where these are.
        if (b.scopeEmpty) return html`Pick what to watch in <a href="#">GitHub's settings</a>, and your work shows up here.`;
        return boardHeadline(b, (_, text) => html`<a href="#">${text}</a>`);
    }

    render() {
        if (!this._loaded) return html`<div class="skeleton say-sk"></div>`;
        if (this._error) return html`<p class="error" role="alert">${this._error}</p>`;
        const b = this._board;
        const fetching = !!this._state?.running?.refresh;
        const failed = !fetching && this._state?.failed?.refresh;
        return html`<p class="say">${this._say()}</p>
            <div class="meta">${b ? html`
                <span>Runner ${b.runnerConnected ? 'online' : 'offline'}</span>
                <span>${fetching ? html`<span class="running" role="status">${spinner}Fetching from GitHub</span>`
                    : html`Updated ${ago(b.fetchedAt)}`}</span>` : ''}
                ${failed ? html`<span class="error">The last refresh failed</span>` : ''}</div>`;
    }
}

customElements.define('gh-card', GhCard);
