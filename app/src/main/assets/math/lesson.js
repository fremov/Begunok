(() => {
  const theme = new URLSearchParams(location.search).get('theme');
  document.documentElement.dataset.theme = theme === 'dark' ? 'dark' : 'light';
  document.addEventListener('click', (event) => {
    const anchor = event.target.closest('a');
    if (!anchor) return;
    if (anchor.dataset.answer) {
      event.preventDefault();
      const selector = anchor.dataset.answer;
      if (!/^\.[A-Za-z_][A-Za-z0-9_-]*$/.test(selector)) return;
      const targets = document.querySelectorAll(selector);
      const open = anchor.getAttribute('aria-expanded') !== 'true';
      for (const target of targets) {
        target.dataset.open = String(open);
        target.style.display = open ? 'block' : 'none';
      }
      anchor.setAttribute('aria-expanded', String(open));
      anchor.textContent = open ? 'Скрыть решение' : 'Показать решение';
    } else if (anchor.dataset.lessonId && window.parent !== window) {
      event.preventDefault();
      window.parent.postMessage({type:'mylife-math-open',lessonId:anchor.dataset.lessonId}, '*');
    }
  });
})();
