// Sidebar presentation only; filtering remains in the existing forms and handlers.
(() => {
  const pinned = new Set();
  const initialized = new WeakSet();
  function initialize() {
    for (const sidebar of document.querySelectorAll('[data-filter-sidebar]')) {
      if (initialized.has(sidebar)) continue;
      initialized.add(sidebar);
      const pin = sidebar.querySelector('[data-filter-pin]');
      function showPin() {
        const active = pinned.has(sidebar.id);
        pin.setAttribute('aria-pressed', String(active));
        pin.textContent = active ? 'Unpin filters' : 'Pin filters';
      }
      sidebar.open = pinned.has(sidebar.id);
      showPin();
      sidebar.addEventListener('mouseenter', () => { sidebar.open = true; });
      sidebar.addEventListener('mouseleave', () => { sidebar.open = pinned.has(sidebar.id); });
      sidebar.addEventListener('focusout', event => {
        if (!sidebar.contains(event.relatedTarget)) sidebar.open = pinned.has(sidebar.id);
      });
      sidebar.querySelector('summary').addEventListener('click', event => {
        if (pinned.has(sidebar.id)) event.preventDefault();
      });
      pin.addEventListener('click', () => {
        if (pinned.has(sidebar.id)) pinned.delete(sidebar.id);
        else pinned.add(sidebar.id);
        sidebar.open = true;
        showPin();
      });
    }
  }
  for (const event of ['DOMContentLoaded', 'htmx:load']) document.addEventListener(event, initialize);
  initialize();
})();
