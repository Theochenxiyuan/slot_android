// The native container supplies local=1. A direct cart remains scoped even if its URL is invalid.
export function studioMode(search) {
  const params = new URLSearchParams(search);
  const single = params.has('platform') || params.has('file');
  const platform = params.get('platform');
  const file = params.get('file');
  const valid = ['GBA', 'GBC', 'GB'].includes(platform) && !!file
    && !/[\\/\x00-\x1f]/.test(file) && !file.startsWith('.');
  return { local: params.get('local') === '1', single, target: single && valid ? { platform, file } : null };
}

export function singleListing(listing, mode) {
  if (!mode.single) return listing;
  const carts = mode.target ? listing.carts.filter((c) => c.platform === mode.target.platform && c.file === mode.target.file) : [];
  return { ...listing, carts };
}

// Modal cancellation must dispatch the dialog's own handler, resolving pending decisions as well.
export function studioBack(doc, editor, gameMenu, busy) {
  for (const id of ['decision', 'reauth', 'fill']) {
    const dialog = doc.getElementById(id);
    if (!dialog?.open) continue;
    if (!busy()) {
      const event = new Event('cancel', { cancelable: true });
      if (dialog.dispatchEvent(event)) dialog.close();
    }
    return true;
  }
  if (!doc.getElementById('game-menu').hidden) { gameMenu.close(true); return true; }
  if (editor.current()) { editor.back(); return true; }
  return busy();
}
