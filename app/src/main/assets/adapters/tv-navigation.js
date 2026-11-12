(function () {
  if (window.__bahaTVNavigation) return;
  window.__bahaTVNavigation = true;
  const style = document.createElement('style');
  style.textContent = ':focus{outline:4px solid #65d4c5!important;outline-offset:3px!important}';
  document.head?.append(style);
  document.addEventListener('keydown', event => {
    const direction = {ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1]}[event.key];
    if (!direction || /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement?.tagName || '')) return;
    const controls = [...document.querySelectorAll('a[href],button,input,select,textarea,[role="button"],[tabindex]')]
      .filter(node => !node.disabled && node.getBoundingClientRect().width > 0 &&
        node.getBoundingClientRect().height > 0 && getComputedStyle(node).visibility !== 'hidden');
    if (!controls.length) return;
    const current = document.activeElement;
    if (!controls.includes(current)) {
      event.preventDefault(); controls[0].focus(); return;
    }
    const rect = current.getBoundingClientRect(), x = rect.x + rect.width / 2, y = rect.y + rect.height / 2;
    let best, score = Infinity;
    for (const node of controls) {
      if (node === current) continue;
      const r = node.getBoundingClientRect(), dx = r.x + r.width / 2 - x, dy = r.y + r.height / 2 - y;
      const ahead = dx * direction[0] + dy * direction[1];
      const cross = Math.abs(dx * direction[1] - dy * direction[0]);
      if (ahead <= 2) continue;
      const candidate = ahead + cross * 3;
      if (candidate < score) { best = node; score = candidate; }
    }
    if (best) {
      event.preventDefault(); best.focus(); best.scrollIntoView({block: 'nearest', inline: 'nearest'});
    }
  }, true);
})(); true;
