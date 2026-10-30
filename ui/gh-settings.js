// The desk's GitHub settings (desk-settings.js), with its calls as the GitHub elf's actions: the gh accounts the board
// works as, what it watches and how it refreshes are the elf's, in its state/ folder. None of them writes to GitHub.
import { LitElement, html, css, shared, spinner } from '/elf-kit/1/kit.js';
import { act, errorMessage, settingsView } from './api.js';

/**
 * The accounts the board works as, what it watches (orgs, users, single repos, exclusions) and the refresh interval:
 * the GitHub tab's own settings, shown in its Settings (embedded).
 */
class GhSettings extends LitElement {

    static properties = {
        embedded: { type: Boolean },
        _settings: { state: true },
        _suggestions: { state: true },
        _picked: { state: true },
        _excludeFor: { state: true },
        _busy: { state: true },
        _error: { state: true },
    };

    static styles = [shared, css`
        .page { display: grid; gap: 30px; }
        h1 { font-size: 34px; line-height: 1.15; font-weight: 600; letter-spacing: -0.022em; }
        .intro { color: var(--muted); margin: 0; max-width: 40rem; }
        section { display: grid; gap: 12px; }
        .head { display: grid; gap: 2px; }
        h2 { font-size: 18px; font-weight: 600; letter-spacing: -0.01em; }
        .hint { font-size: 14px; color: var(--muted); margin: 0; }
        ul { list-style: none; margin: 0; padding: 4px 0; }
        li { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px 14px; align-items: center; padding: 12px 18px; }
        li + li { border-top: 1px solid var(--line); }
        .name { font-weight: 500; overflow-wrap: anywhere; }
        .row-main { min-width: 0; display: grid; gap: 6px; }
        .acts { display: flex; flex-wrap: wrap; gap: 8px; justify-content: flex-end; }
        form.add { display: flex; flex-wrap: wrap; gap: 8px; }
        form.add input { flex: 1 1 16rem; min-width: 0; }
        .panel > form.add { padding: 14px 18px; border-top: 1px solid var(--line); }
        .empty { color: var(--muted); font-size: 14px; padding: 14px 18px; margin: 0; }
        label.pick { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 12px; align-items: center; cursor: pointer; }
        input[type="checkbox"] { width: 17px; height: 17px; accent-color: var(--accent); margin: 0; }
        .chip button {
            all: unset; cursor: pointer; margin-left: 2px; padding: 0 2px; border-radius: 4px; color: var(--muted);
        }
        .chip button:hover { color: var(--bad); }
        .chip button:focus-visible { outline: 2px solid var(--accent); }
        .first { padding: 16px 18px; display: flex; flex-wrap: wrap; gap: 10px; border-top: 1px solid var(--line); }
        .general { padding: 14px 18px; display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
        code { font: 13px var(--font-code); background: var(--surface-2); padding: 2px 6px; border-radius: 5px; }
        .sk { height: 52px; }
        .account { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px 14px; align-items: center; }
        .account .login { font-size: 13.5px; color: var(--muted); }
        .account input { width: min(16rem, 100%); }
        form.add.two input { flex: 1 1 11rem; }
        .acct {
            display: inline-block; font-size: 12px; line-height: 1.5; padding: 0 7px; border-radius: 999px; white-space: nowrap;
            border: 1px solid var(--line); color: var(--muted); font-weight: 400;
        }
        .named { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 8px; min-width: 0; }
        @media (max-width: 600px) {
            h1 { font-size: 26px; }
            li { grid-template-columns: minmax(0, 1fr); padding: 12px 14px; }
            .acts { justify-content: flex-start; }
        }
    `];

    constructor() {
        super();
        this._picked = new Set();
        this._busy = null;
    }

    connectedCallback() {
        super.connectedCallback();
        this._load();
    }

    async _load() {
        try {
            // What the elf's last run wrote; before its first run, the elf says.
            this._settings = (await settingsView()) ?? await act('settings');
        } catch (e) {
            this._error = `Couldn't load settings: ${errorMessage(e)}`;
            return;
        }
        try {
            // Orgs you might watch, from GitHub: without them the settings still work.
            this._suggestions = await act('suggestions');
            if (this._firstRun()) {
                this._picked = new Set(this._suggestions.filter(s => s.preselect).map(s => s.owner));
            }
        } catch {
            this._suggestions = [];
        }
    }

    _firstRun() {
        return this._settings && this._settings.scope.every(r => r.mode !== 'include');
    }

    /** Runs one action that answers with the new settings, then refreshes suggestions. `what` names the busy control. */
    async _do(what, call) {
        this._busy = what;
        this._error = null;
        try {
            this._settings = await call();
            this._suggestions = await act('suggestions').catch(() => this._suggestions);
            return true;
        } catch (e) {
            this._error = errorMessage(e);
            return false;
        } finally {
            this._busy = null;
        }
    }

    async _add(e) {
        e.preventDefault();
        const input = e.target.querySelector('input');
        const name = input.value.trim();
        if (!name) return;
        if (await this._do('add', () => act('watch', { name }))) input.value = '';
    }

    async _exclude(e, owner) {
        e.preventDefault();
        const input = e.target.querySelector('input');
        let repo = input.value.trim();
        if (!repo) return;
        if (!repo.includes('/')) repo = `${owner}/${repo}`;
        if (await this._do(`exclude:${owner}`, () => act('exclude', { repo }))) {
            input.value = '';
            this._excludeFor = null;
        }
    }

    _togglePick(owner) {
        const next = new Set(this._picked);
        next.has(owner) ? next.delete(owner) : next.add(owner);
        this._picked = next;
    }

    _spin(what) {
        return this._busy === what ? spinner : '';
    }

    render() {
        const s = this._settings;
        return html`
            <div class="page">
                ${this.embedded ? '' : html`<div><h1>Settings</h1></div>`}
                <p class="intro">Choose which orgs and repos the board watches. Anything else that involves you is collected
                    at the bottom of the board under "repos you don't watch".</p>
                ${this._error ? html`<p class="error" role="alert">${this._error}</p>` : ''}
                ${s ? html`
                    ${this._renderAccounts(s)}
                    ${this._firstRun() ? this._renderFirstRun() : this._renderScope(s)}
                    ${this._firstRun() ? '' : this._renderSuggestions()}
                    ${this._renderGeneral(s)}
                ` : (this._error ? '' : html`
                    <div aria-busy="true" style="display:grid;gap:12px">
                        <div class="skeleton" style="height:18px;width:200px"></div>
                        <div class="skeleton sk"></div><div class="skeleton sk"></div><div class="skeleton sk"></div>
                    </div>`)}
            </div>
        `;
    }

    /** The gh accounts the board works as, each with a name you give it: its items are tagged with it. */
    _renderAccounts(s) {
        const accounts = s.accounts?.length ? s.accounts : [{ login: s.login, name: s.login }];
        return html`
            <section aria-labelledby="accounts">
                <div class="head"><h2 id="accounts">Accounts</h2>
                    <p class="hint">The board shows what involves any of these, each item tagged with its account's name, and
                        does what you click as that account. Each one must be logged in to gh on this machine
                        (<code>gh auth login</code>). The first is the main account, set under Its setup below.</p></div>
                <div class="panel">
                    <ul>${accounts.map((a, i) => html`
                        <li class="account">
                            <div class="row-main">
                                <label for="name-${a.login}" class="name">${a.login}</label>
                                <input type="text" id="name-${a.login}" .value=${a.name === a.login ? '' : a.name}
                                    placeholder="A name, for example Work" maxlength="30" ?disabled=${this._busy !== null}
                                    @change=${e => this._rename(a, e.target.value)}>
                            </div>
                            <div class="acts">
                                ${this._spin(`name:${a.login}`)}
                                ${i === 0 ? '' : html`<button class="sm" ?disabled=${this._busy !== null}
                                    @click=${() => this._do(`unaccount:${a.login}`, () => act('remove-account', { login: a.login }))}>
                                    ${this._spin(`unaccount:${a.login}`)}Remove</button>`}
                            </div>
                        </li>`)}
                    </ul>
                    <form class="add two" @submit=${this._addAccount}>
                        <input type="text" name="login" aria-label="gh login to add" placeholder="GitHub login" autocomplete="off">
                        <input type="text" name="name" aria-label="Its name" placeholder="Name, for example Personal" maxlength="30">
                        <button type="submit" ?disabled=${this._busy !== null}>${this._spin('account')}Add account</button>
                    </form>
                </div>
            </section>
        `;
    }

    async _rename(a, value) {
        const name = value.trim() || a.login;
        if (name === a.name) return;
        await this._do(`name:${a.login}`, () => act('rename-account', { login: a.login, name }));
    }

    async _addAccount(e) {
        e.preventDefault();
        const form = e.target;
        const login = form.elements.namedItem('login').value.trim();
        const name = form.elements.namedItem('name').value.trim();
        if (!login) return;
        if (await this._accounts('account', () => act('add-account', { login, name }))) form.reset();
    }

    /** An account added: the board fetches again, for its items. */
    async _accounts(what, call) {
        const ok = await this._do(what, call);
        if (ok) this.dispatchEvent(new CustomEvent('board-search-changed', { bubbles: true, composed: true }));
        return ok;
    }

    /** With more than one account: the names of those with items there (as the last refresh found them). */
    _accountTags(logins) {
        const accounts = this._settings?.accounts ?? [];
        if (accounts.length < 2 || !logins?.length) return '';
        const name = l => accounts.find(a => a.login.toLowerCase() === l.toLowerCase())?.name ?? l;
        return logins.map(l => html`<span class="acct" title="${l} has items here">${name(l)}</span>`);
    }

    _renderFirstRun() {
        const sug = this._suggestions ?? [];
        return html`
            <section aria-labelledby="pick">
                <div class="head"><h2 id="pick">Pick what to watch</h2>
                    <p class="hint">These orgs and users have open items involving you. The orgs you belong to are already ticked.</p></div>
                <div class="panel">
                    ${sug.length === 0 ? html`<p class="empty">No suggestions. Add an org or repo below.</p>` : html`
                        <ul>${sug.map(x => html`
                            <li><label class="pick">
                                <input type="checkbox" .checked=${this._picked.has(x.owner)} @change=${() => this._togglePick(x.owner)}>
                                <span class="row-main"><span class="named"><span class="name">${x.owner}</span>${this._accountTags(x.accounts)}</span>
                                    <span class="chips">
                                        <span class="chip">${x.items} open item${x.items === 1 ? '' : 's'}</span>
                                        ${x.member ? html`<span class="chip">Member</span>` : ''}
                                    </span></span>
                            </label></li>`)}
                        </ul>`}
                    <div class="first">
                        <button class="primary" ?disabled=${this._busy !== null || this._picked.size === 0}
                                @click=${() => this._do('pick', () => act('watch-all', { owners: [...this._picked].join(',') }))}>
                            ${this._spin('pick')}Watch ${this._picked.size} selected
                        </button>
                    </div>
                    ${this._renderAddForm()}
                </div>
            </section>
        `;
    }

    _renderScope(s) {
        const owners = s.scope.filter(r => r.kind === 'owner');
        const repos = s.scope.filter(r => r.kind === 'repo' && r.mode === 'include');
        const excluded = s.scope.filter(r => r.mode === 'exclude');
        return html`
            <section aria-labelledby="orgs">
                <div class="head"><h2 id="orgs">Orgs and users</h2>
                    <p class="hint">Every public repo they own, apart from any you exclude.</p></div>
                <div class="panel">
                    ${owners.length === 0 ? html`<p class="empty">None yet.</p>` : html`<ul>${owners.map(o => {
                        const ex = excluded.filter(x => x.name.startsWith(`${o.name}/`));
                        return html`
                            <li>
                                <div class="row-main">
                                    <span class="named"><span class="name">${o.name}</span>${this._accountTags(o.accounts)}</span>
                                    ${ex.length ? html`<div class="chips">${ex.map(x => html`
                                        <span class="chip">Not ${x.name.slice(o.name.length + 1)}<button
                                            aria-label="Watch ${x.name} again" title="Watch it again" ?disabled=${this._busy !== null}
                                            @click=${() => this._do(`rm:${x.id}`, () => act('remove', { id: x.id }))}>×</button></span>`)}</div>` : ''}
                                    ${this._excludeFor === o.name ? html`
                                        <form class="add" @submit=${e => this._exclude(e, o.name)}>
                                            <input type="text" id="exclude-${o.name}" aria-label="Repo to exclude from ${o.name}"
                                                   placeholder="Repo name, for example quarkus-quickstarts" autofocus>
                                            <button class="sm" type="submit" ?disabled=${this._busy !== null}>${this._spin(`exclude:${o.name}`)}Exclude</button>
                                            <button class="sm quiet" type="button" @click=${() => { this._excludeFor = null; }}>Cancel</button>
                                        </form>` : ''}
                                </div>
                                <div class="acts">
                                    <button class="sm quiet" @click=${() => { this._excludeFor = o.name; }}>Exclude a repo</button>
                                    <button class="sm" ?disabled=${this._busy !== null} @click=${() => this._do(`rm:${o.id}`, () => act('remove', { id: o.id }))}>
                                        ${this._spin(`rm:${o.id}`)}Remove</button>
                                </div>
                            </li>`;
                    })}</ul>`}
                </div>
            </section>
            <section aria-labelledby="repos">
                <div class="head"><h2 id="repos">Single repos</h2>
                    <p class="hint">Repos outside the orgs above. Paste a GitHub URL or type <code>owner/repo</code>, or an org name to watch the whole org.</p></div>
                <div class="panel">
                    ${repos.length === 0 ? html`<p class="empty">None yet.</p>` : html`<ul>${repos.map(r => html`
                        <li><span class="named"><span class="name">${r.name}</span>${this._accountTags(r.accounts)}</span>
                            <div class="acts"><button class="sm" ?disabled=${this._busy !== null}
                                @click=${() => this._do(`rm:${r.id}`, () => act('remove', { id: r.id }))}>${this._spin(`rm:${r.id}`)}Remove</button></div>
                        </li>`)}</ul>`}
                    ${this._renderAddForm()}
                </div>
            </section>
            ${this._renderMaintained(s)}
        `;
    }

    /** Repos you maintain: the board shows all their open PRs and issues, so none is missed. */
    _renderMaintained(s) {
        const repos = s.alwaysInvolved ?? [];
        return html`
            <section aria-labelledby="maintained">
                <div class="head"><h2 id="maintained">Repos I maintain</h2>
                    <p class="hint">Every open PR in these comes to Needs my review, and every open issue nobody else is
                        assigned to is your move, even when GitHub doesn't count you as involved.</p></div>
                <div class="panel">
                    ${repos.length === 0 ? html`<p class="empty">None yet.</p>` : html`<ul>${repos.map(r => html`
                        <li><span class="named"><span class="name">${r}</span>${this._accountTags(s.maintainedBy?.[r])}</span>
                            <div class="acts"><button class="sm" ?disabled=${this._busy !== null}
                                @click=${() => this._maintained(`unmaintain:${r}`, () => act('remove-always-involved', { repo: r }))}>
                                ${this._spin(`unmaintain:${r}`)}Remove</button></div>
                        </li>`)}</ul>`}
                    <form class="add" @submit=${this._addMaintained}>
                        <input type="text" id="add-maintained" aria-label="owner/repo you maintain" placeholder="quarkusio/quarkus-agent-mcp">
                        <button type="submit" ?disabled=${this._busy !== null}>${this._spin('maintain')}Add</button>
                    </form>
                </div>
            </section>
        `;
    }

    async _addMaintained(e) {
        e.preventDefault();
        const input = e.target.querySelector('input');
        const repo = input.value.trim();
        if (!repo) return;
        if (await this._maintained('maintain', () => act('add-always-involved', { repo }))) input.value = '';
    }

    /** A change to the repos you maintain: the board fetches again, as their search changed. */
    async _maintained(what, call) {
        const ok = await this._do(what, call);
        if (ok) this.dispatchEvent(new CustomEvent('board-search-changed', { bubbles: true, composed: true }));
        return ok;
    }

    _renderAddForm() {
        return html`
            <form class="add" @submit=${this._add}>
                <input type="text" id="add-scope" aria-label="Org, user, or owner/repo to watch" placeholder="quarkiverse, or mvnpm/mvnpm">
                <button type="submit" ?disabled=${this._busy !== null}>${this._spin('add')}Add</button>
            </form>
        `;
    }

    _renderSuggestions() {
        const sug = (this._suggestions ?? []).filter(x => x.items > 0);
        if (sug.length === 0) return '';
        return html`
            <section aria-labelledby="suggested">
                <div class="head"><h2 id="suggested">Suggested</h2>
                    <p class="hint">Not watched yet, but these have open items that involve you.</p></div>
                <div class="panel">
                    <ul>${sug.map(x => html`
                        <li>
                            <div class="row-main"><span class="named"><span class="name">${x.owner}</span>${this._accountTags(x.accounts)}</span>
                                <div class="chips"><span class="chip">${x.items} open item${x.items === 1 ? '' : 's'}</span>
                                    ${x.member ? html`<span class="chip">Member</span>` : ''}</div></div>
                            <div class="acts"><button class="sm" ?disabled=${this._busy !== null}
                                @click=${() => this._do(`watch:${x.owner}`, () => act('watch', { name: x.owner }))}>${this._spin(`watch:${x.owner}`)}Watch</button></div>
                        </li>`)}</ul>
                </div>
            </section>
        `;
    }

    _renderGeneral(s) {
        return html`
            <section aria-labelledby="general">
                <div class="head"><h2 id="general">Board</h2></div>
                <div class="panel general">
                    <label for="refresh">Refresh while it's open, every</label>
                    <select id="refresh" ?disabled=${this._busy !== null}
                            @change=${e => {
                                // Read now: the event's target is gone once _do awaits.
                                const minutes = Number(e.target.value);
                                this._do('refresh', () => act('set-refresh-minutes', { minutes }));
                            }}>
                        ${[2, 5, 10, 15, 30, 60].map(m => html`<option value=${m} ?selected=${m === s.refreshMinutes}>${m} minutes</option>`)}
                    </select>
                    ${this._spin('refresh')}
                </div>
            </section>
        `;
    }
}

customElements.define('gh-settings', GhSettings);
