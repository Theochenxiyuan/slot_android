export const KINDS = ['card', 'real', 'logo', 'customLogo', 'customLabel'];

const OTHER = { real: 'logo', logo: 'real' };

export function available(c, kind) {
  if (kind === 'card') return c.onCard;
  if (kind === 'real') return c.hasReal;
  if (kind === 'logo') return c.hasLogo;
  return true;
}

export function wears(c) {
  const why = c.byHand ? 'byHand' : 'fill';
  switch (c.choice) {
    case 'card':
      return { kind: 'card', why: 'card' };
    case 'none':
      return { kind: 'none', why };
    case 'customLogo':
      return c.customLogoBytes ? { kind: 'customLogo', why } : { kind: 'none', why: 'nothing' };
    case 'customLabel':
      return c.customLabelBytes ? { kind: 'customLabel', why } : { kind: 'none', why: 'nothing' };
    case 'real':
    case 'logo':
      if (available(c, c.choice)) return { kind: c.choice, why };
      if (available(c, OTHER[c.choice])) return { kind: OTHER[c.choice], why: 'fallback' };
      return { kind: 'none', why: 'nothing' };
    default:
      return { kind: 'none', why: 'unfilled' };
  }
}

const fillable = (c) => !c.onCard && !c.error && !c.byHand && c.choice == null;

export function applyFill(carts, fill) {
  let n = 0;
  for (const c of carts) {
    if (!fillable(c)) continue;
    c.choice = fill;
    n++;
  }
  return n;
}

export function fillCounts(carts) {
  const counts = { unlabelled: 0, real: 0, logo: 0, neither: 0 };
  for (const c of carts) {
    if (!fillable(c)) continue;
    counts.unlabelled++;
    if (c.hasReal) counts.real++;
    if (c.hasLogo) counts.logo++;
    if (!c.hasReal && !c.hasLogo) counts.neither++;
  }
  return counts;
}

export function fillText({ unlabelled, covers = 0 }) {
  const title = unlabelled === 1 ? '1 cart has no label yet' : `${unlabelled} carts have no label yet`;
  const base = unlabelled === 1 ? 'How should the studio fill it?' : 'How should the studio fill them?';
  if (covers <= 0) return { title, question: base };
  const suffix = covers === 1 ? '1 matched cover will also be saved.' : `${covers} matched covers will also be saved.`;
  return { title, question: `${base} ${suffix}` };
}

export function shelfFillBar({ unlabelled = 0, fillable = 0, covers = 0 }) {
  if (unlabelled === 0 && covers === 0) return { hidden: true, mode: null, status: '', action: '' };
  const uNoun = unlabelled === 1 ? '1 unlabelled game' : `${unlabelled} unlabelled games`;
  const cNoun = covers === 1 ? '1 cover not saved yet' : `${covers} covers not saved yet`;
  if (unlabelled > 0 && covers === 0) {
    return {
      hidden: false,
      mode: fillable > 0 ? 'labels' : null,
      status: fillable > 0 ? `${uNoun} · ${fillable} can be generated` : uNoun,
      action: fillable > 0 ? `Generate labels (${fillable})` : '',
    };
  }
  if (unlabelled === 0) {
    return {
      hidden: false,
      mode: 'covers',
      status: cNoun,
      action: `Save covers (${covers})`,
    };
  }
  return {
    hidden: false,
    mode: fillable > 0 ? 'both' : 'covers',
    status: `${uNoun} · ${cNoun}`,
    action: fillable > 0 ? `Fill labels and covers (${fillable + covers})` : `Save covers (${covers})`,
  };
}

export function autoFill({ real, logo }) {
  if (real > 0 && logo > 0) return null;
  return real > 0 ? 'real' : 'logo';
}
