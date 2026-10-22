// The GitHub elf's tab on Elf Desk, its dashboard card, and its settings (in the desk's Settings pop-up). The tab is the desk's GitHub board, moved here: the
// board (gh-board.js), and one item's thread page as a place in the tab (thread/owner/repo/123, the desk's link
// #github/thread/owner/repo/123; its old #thread/owner/repo/123 links arrive here the same). It runs in the desk's
// sandboxed frame and reads the files the elf writes; every GitHub write waits for your click in the desk's bar.
import { LitElement, html, elf } from '/elf-kit/1/kit.js';
import { itemKeyFromRoute } from './shared.js';
import { go, onGo } from './api.js';

/** The tab: the board, or the thread page its route names. */
class GhTab extends LitElement {

    static properties = {
        _route: { state: true },
    };

    constructor() {
        super();
        this._route = elf.route();
    }

    connectedCallback() {
        super.connectedCallback();
        this._off = onGo(route => {
            if (route === this._route) return;
            this._route = route;
            // The frame is as tall as what it shows, so the desk's page scrolls: a new place starts at its top.
            this.updateComplete.then(() => this.scrollIntoView({ block: 'start' }));
        });
    }

    disconnectedCallback() {
        this._off?.();
        super.disconnectedCallback();
    }

    render() {
        const key = itemKeyFromRoute(this._route);
        return key ? html`<gh-thread .itemKey=${key}></gh-thread>` : html`<gh-board></gh-board>`;
    }
}

/**
 * Links in the tab to a thread page (#thread/owner/repo/123) or back to the board (#github) go there in the tab,
 * as the desk's hash links did. Caught before the kit's own handler, which opens the tab for any other # link.
 */
function followLinks(e) {
    if (e.defaultPrevented || e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;
    const a = e.composedPath().find(n => n instanceof HTMLAnchorElement);
    const href = a?.getAttribute('href') ?? '';
    if (href === '#github' || href === '#') {
        e.preventDefault();
        go('');
    } else if (itemKeyFromRoute(href.slice(1)) && href.startsWith('#')) {
        e.preventDefault();
        go(href.slice(1));
    }
}

if (elf.view === 'card') {
    await import('./gh-card.js');
    document.body.append(document.createElement('gh-card'));
} else if (elf.view === 'settings') {
    // What the board watches, at the top of the desk's Settings pop-up for this elf.
    await import('./gh-settings.js');
    const settings = document.createElement('gh-settings');
    settings.embedded = true;
    // The repos you maintain are part of the board's search: it fetches again.
    settings.addEventListener('board-search-changed', () => elf.run('refresh').catch(() => {}));
    document.body.append(settings);
} else {
    await import('./gh-board.js');
    customElements.define('gh-tab', GhTab);
    window.addEventListener('click', followLinks, true);
    document.body.append(document.createElement('gh-tab'));
}
