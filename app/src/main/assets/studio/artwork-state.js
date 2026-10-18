// Optional download state is separate from the editable appearance draft.
export function artworkStatus(c) {
  if (c.error) return null;
  if (c.reading) return { text: 'Reading the game…', busy: true };
  if (c.matchState === 'matching') return { text: 'Matching game…', busy: true };
  if (c.matchState === 'loading' || c.boxFetching || c.real?.loading)
    return { text: 'Fetching images…', busy: true };
  if (c.matchState === 'error') return { text: 'Game matching failed', action: 'Retry' };
  if (Object.values(c.artStates ?? {}).includes('error') || c.real?.failed)
    return { text: 'Couldn’t fetch images', action: 'Retry' };
  if (c.matchState === 'ready' && (!c.game || c.rejected))
    return { text: 'No matching game', action: 'Choose a game' };
  return null;
}

export function imageState(c, kind) {
  if (kind === 'real' && c.real?.loading) return 'loading';
  if (kind === 'real' && c.real?.failed) return 'error';
  if (kind === 'real' && c.real?.bytes) return 'ready';
  if (kind === 'real' && c.real?.missing) return 'empty';
  const state = c.artStates?.[kind];
  if (state === 'ready' || state === 'error' || state === 'empty') return state;
  if (c.reading || c.matchState === 'matching' || c.matchState === 'loading') return 'loading';
  if (c.matchState === 'error') return 'error';
  return state ?? 'empty';
}
