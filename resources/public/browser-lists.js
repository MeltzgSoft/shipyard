// List transport; workspace and selection state remain server-owned.
(() => {
  const thumbnails = '.part-thumbnail[hx-get], .ship-thumbnail[hx-get]';
  let scheduled = false;
  function refresh() {
    scheduled = false;
    const library = document.querySelector('#library');
    if (!library) return;
    const matching = library.querySelector('#part-select-matching');
    if (matching) matching.indeterminate = matching.dataset.indeterminate === 'true';
    for (const root of library.querySelectorAll('#bulk-orient-results, #ship-results')) {
      const pages = [...root.querySelectorAll('[data-list-page]')].map(e => Number(e.dataset.listPage));
      const input = root.querySelector('[data-part-page], [data-ship-page]');
      if (input && pages.length) input.value = String(Math.max(...pages));
    }
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
    if (source.closest('.list-more, .part-drawer__content') || source.closest(thumbnails)) {
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

// Part editor drafts are local until their existing save forms succeed.
// Confirmation runs before HTMX configures or sends a workspace transition.
(() => {
  const baselines = new WeakMap();
  function value(form) {
    return JSON.stringify([...form.querySelectorAll('input[name], select[name], textarea[name]')]
      .map(e => [e.name, e.type === 'checkbox' || e.type === 'radio' ? e.checked : e.value]));
  }
  function capture() {
    for (const form of document.querySelectorAll('#detail .part-metadata__form, #detail .part-orientation__form, #detail .mount-wizard__form')) {
      if (!baselines.has(form)) baselines.set(form, value(form));
    }
  }
  function dirty(root) {
    const mount = root.querySelector('.mount-wizard__form');
    if (mount?.dataset.mountDraft === 'true') return true;
    return [...root.querySelectorAll('.part-metadata__form, .part-orientation__form, .mount-wizard__form')].some(form => baselines.has(form) && baselines.get(form) !== value(form));
  }
  document.addEventListener('htmx:confirm', event => {
    const button = event.detail.elt.closest('[data-part-nav], [data-part-back]');
    const root = button?.closest('.detail, .detail__head');
    if (!root || !dirty(root.closest('.detail') || root)) return;
    event.preventDefault();
    if (window.confirm('Discard unsaved changes and leave this part?')) event.detail.issueRequest(true);
  });
  for (const name of ['DOMContentLoaded', 'htmx:load', 'htmx:afterSwap']) document.addEventListener(name, capture);
  capture();
})();
