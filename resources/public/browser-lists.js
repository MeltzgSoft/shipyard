// List transport and progress; workspace and selection state remain server-owned.
(() => {
  const thumbnails = '.part-thumbnail[hx-get], .ship-thumbnail[hx-get]';
  let scheduled = false;
  function refresh() {
    scheduled = false;
    const library = document.querySelector('#library');
    if (!library) return;
    for (const root of library.querySelectorAll('#bulk-orient-results, #ship-results')) {
      const pages = [...root.querySelectorAll('[data-list-page]')].map(e => Number(e.dataset.listPage));
      const input = root.querySelector('[data-part-page], [data-ship-page]');
      if (input && pages.length) input.value = String(Math.max(...pages));
    }
    const progress = library.querySelector('[data-thumbnail-progress]');
    if (!progress) return;
    const counts = {ready: 0, generating: 0, waiting: 0, unavailable: 0, skipped: 0};
    for (const el of library.querySelectorAll('.part-thumbnail, .ship-thumbnail')) {
      const state = !el.hasAttribute('hx-get') ? 'skipped'
        : el.querySelector('img') ? 'ready'
        : el.querySelector('[hx-get]') || el.classList.contains('htmx-request') ? 'generating'
        : el.textContent.includes('…') ? 'waiting' : 'unavailable';
      counts[state]++;
    }
    const text = `Loaded thumbnails: ${counts.ready} ready · ${counts.generating} generating · ${counts.waiting} waiting`
      + (counts.unavailable ? ` · ${counts.unavailable} unavailable` : '')
      + (counts.skipped ? ` · ${counts.skipped} no preview` : '');
    if (progress.textContent !== text) progress.textContent = text;
  }
  function schedule() {
    if (!scheduled) { scheduled = true; requestAnimationFrame(refresh); }
  }
  document.addEventListener('htmx:configRequest', event => {
    const table = event.detail.elt.closest('.bulk-orient__table');
    if (table && event.detail.path === '/orient/selection') {
      event.detail.parameters.visible = JSON.stringify([...table.querySelectorAll('[data-bulk-select]')].map(e => e.value));
    }
  });
  document.addEventListener('htmx:beforeRequest', event => {
    const source = event.detail.elt;
    if (source.closest('.list-more') || source.closest(thumbnails)) {
      const xhr = event.detail.xhr;
      const guard = e => { if (e.detail.xhr === xhr && !source.isConnected) e.detail.shouldSwap = false; };
      document.addEventListener('htmx:beforeSwap', guard);
      xhr.addEventListener('loadend', () => document.removeEventListener('htmx:beforeSwap', guard), {once: true});
    }
    schedule();
  });
  document.addEventListener('htmx:afterRequest', event => {
    const thumbnail = event.detail.elt.closest(thumbnails);
    if (thumbnail && thumbnail.isConnected && event.detail.failed) {
      thumbnail.textContent = 'Preview unavailable';
      schedule();
    }
  });
  document.addEventListener('htmx:sendError', event => {
    const thumbnail = event.detail.elt.closest(thumbnails);
    if (thumbnail && thumbnail.isConnected) thumbnail.textContent = 'Preview unavailable';
    schedule();
  });
  document.addEventListener('error', event => {
    if (event.target.tagName === 'IMG') {
      const thumbnail = event.target.closest(thumbnails);
      if (thumbnail) { thumbnail.textContent = 'Preview unavailable'; schedule(); }
    }
  }, true);
  for (const event of ['DOMContentLoaded', 'htmx:load', 'htmx:afterRequest', 'htmx:afterSwap']) {
    document.addEventListener(event, schedule);
  }
})();
