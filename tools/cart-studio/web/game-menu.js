import { decide } from './decision.js';
import { t } from './i18n.js';

// One anchored overlay shared by the shelf cards; no management controls live in the editor.
export function createGameMenu(api) {
  const menu = document.getElementById('game-menu');
  const remove = document.getElementById('game-delete');
  let current = null;
  let anchor = null;
  let confirming = false;

  function close(restore = false) {
    const was = current;
    current = null;
    anchor = null;
    menu.hidden = true;
    was?.el.more.setAttribute('aria-expanded', 'false');
    if (restore && was?.el.more.isConnected) was.el.more.focus({ preventScroll: true });
  }

  function open(c) {
    if (api.busy() || confirming) return;
    if (current === c) { close(true); return; }
    close();
    current = c;
    menu.hidden = false;
    c.el.more.setAttribute('aria-expanded', 'true');
    menu.setAttribute('aria-label', t(`Manage ${c.stem}`));
    const bounds = c.el.more.getBoundingClientRect();
    anchor = { left: bounds.left, top: bounds.top };
    const gap = 6;
    const left = Math.max(8, Math.min(bounds.right - menu.offsetWidth, innerWidth - menu.offsetWidth - 8));
    const top = bounds.bottom + gap + menu.offsetHeight <= innerHeight - 8
      ? bounds.bottom + gap : Math.max(8, bounds.top - menu.offsetHeight - gap);
    menu.style.left = `${left}px`;
    menu.style.top = `${top}px`;
    remove.focus({ preventScroll: true });
  }

  function bind(c) {
    const trigger = c.el.more;
    trigger.hidden = !api.supported();
    trigger.setAttribute('aria-label', t(`Manage ${c.stem}`));
    trigger.addEventListener('click', (event) => { event.stopPropagation(); open(c); });
    trigger.addEventListener('keydown', (event) => {
      if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
      event.preventDefault();
      open(c);
    });
  }

  remove.addEventListener('click', async () => {
    const c = current;
    if (!c || api.busy() || confirming) return;
    close();
    confirming = true;
    // Restore the trigger before opening the dialog so its focus-return target remains on the shelf.
    c.el.more.focus({ preventScroll: true });
    try {
      const answer = await decide({ title: 'Delete game', text: `Delete “${c.file}”? Exclusive labels, covers and shell settings will also be removed. Save files will be kept.`, confirm: 'Delete game' });
      if (answer === null || api.busy()) return;
      const cards = [...document.querySelectorAll('.cart-more:not([hidden])')];
      const index = cards.indexOf(c.el.more);
      const next = cards[index + 1] ?? cards[index - 1];
      await api.remove(c);
      if (next?.isConnected) next.focus({ preventScroll: true });
      else {
        const grid = document.getElementById('grid');
        grid.tabIndex = -1;
        grid.focus({ preventScroll: true });
      }
    } catch (error) {
      api.failed(error);
      if (c.el.more.isConnected) c.el.more.focus({ preventScroll: true });
    } finally { confirming = false; }
  });

  document.addEventListener('pointerdown', (event) => {
    if (current && !menu.contains(event.target) && !current.el.more.contains(event.target)) close();
  });
  document.addEventListener('focusin', (event) => {
    if (current && !menu.contains(event.target) && event.target !== current.el.more) close();
  });
  menu.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); close(true); }
    if (['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) { event.preventDefault(); remove.focus(); }
  });
  window.addEventListener('resize', () => close());
  window.addEventListener('scroll', () => {
    if (!current || !anchor) return;
    const bounds = current.el.more.getBoundingClientRect();
    if (Math.abs(bounds.top - anchor.top) > .5 || Math.abs(bounds.left - anchor.left) > .5) close();
  }, true);
  return { bind, close };
}
