import { t } from './i18n.js';

const $ = (id) => document.getElementById(id);
let pending = null;

// A second dialog keeps keyboard focus in confirmations above the editor.
export function decide({ title, text, confirm = 'Save', discard = false }) {
  if (pending) return Promise.resolve(null);
  $('decision-title').textContent = t(title);
  $('decision-text').textContent = t(text);
  $('decision-confirm').textContent = t(confirm);
  $('decision-discard').hidden = !discard;
  $('decision-cancel').textContent = t(discard ? 'Keep editing' : 'Cancel');
  $('decision').showModal();
  $('decision-cancel').focus();
  return new Promise((resolve) => { pending = resolve; });
}

function finish(value) {
  const resolve = pending;
  pending = null;
  $('decision').close();
  resolve?.(value);
}

$('decision-form').addEventListener('submit', (e) => {
  e.preventDefault();
  finish('save');
});
$('decision-cancel').addEventListener('click', () => finish(null));
$('decision-discard').addEventListener('click', () => finish('discard'));
$('decision').addEventListener('cancel', (e) => { e.preventDefault(); finish(null); });
