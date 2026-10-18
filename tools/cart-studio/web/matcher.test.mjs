import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  extractBracketTags,
  matchFromCatalog,
  normalizeTitle,
  parseCatalog,
  parseTags,
  searchQueryOf,
} from './matcher.js';

const GBA_DAT = `clrmamepro (
	name "Nintendo - Game Boy Advance"
	description "Nintendo - Game Boy Advance"
)

game (
	name "Mother 3 (Japan)"
	region "Japan"
	serial "A3UJ"
	rom ( name "Mother 3 (Japan).gba" size 33554432 crc 20DF8996 serial "A3UJ" )
)

game (
	name "Rhythm Tengoku (Japan)"
	region "Japan"
	serial "BRIJ"
	rom ( name "Rhythm Tengoku (Japan).gba" size 16777216 crc 91DC2E30 serial "BRIJ" )
)

game (
	name "WarioWare - Twisted! (USA)"
	region "USA"
	serial "RZWE"
	rom ( name "WarioWare - Twisted! (USA).gba" size 16777216 crc 7D9F3101 serial "RZWE" )
)

game (
	name "Advance Wars (USA)"
	region "USA"
	serial "AWRE"
	rom ( name "Advance Wars (USA).gba" size 4194304 crc 11111111 serial "AWRE" )
)

game (
	name "Advance Wars (USA) (Rev 1)"
	region "USA"
	serial "AWRE"
	rom ( name "Advance Wars (USA) (Rev 1).gba" size 4194304 crc 26E16D72 serial "AWRE" )
)

game (
	name "Advance Wars (USA) (Virtual Console)"
	region "USA"
	serial "AWRE"
	rom ( name "Advance Wars (USA) (Virtual Console).gba" size 4194304 crc 22222222 serial "AWRE" )
)

game (
	name "Advance Wars (Europe) (En,Fr,De,Es)"
	region "Europe"
	serial "AWRP"
	rom ( name "Advance Wars (Europe) (En,Fr,De,Es).gba" size 8388608 crc 33333333 serial "AWRP" )
)

game (
	name "Super Mario Advance 4 - Super Mario Bros. 3 (USA)"
	region "USA"
	serial "AX4E"
	rom ( name "Super Mario Advance 4 - Super Mario Bros. 3 (USA).gba" size 4194304 crc 44444444 serial "AX4E" )
)

game (
	name "Super Mario Advance 4 - Super Mario Bros. 3 (USA, Australia) (Rev 1)"
	region "USA"
	serial "AX4E"
	rom ( name "Super Mario Advance 4 - Super Mario Bros. 3 (USA, Australia) (Rev 1).gba" size 4194304 crc 88DAB22D serial "AX4E" )
)

game (
	name "Super Mario Advance 4 - Super Mario Bros. 3 (USA) (Switch Online)"
	region "USA"
	serial "AX4E"
	rom ( name "Super Mario Advance 4 - Super Mario Bros. 3 (USA) (Switch Online).gba" size 4194304 crc 55555555 serial "AX4E" )
)

game (
	name "Castlevania - Aria of Sorrow (USA)"
	region "USA"
	serial "A2CE"
	rom ( name "Castlevania - Aria of Sorrow (USA).gba" size 8388608 crc 506708D9 serial "A2CE" )
)

game (
	name "Castlevania - Aria of Sorrow (USA) (Castlevania Advance Collection)"
	region "USA"
	serial "A2CE"
	rom ( name "Castlevania - Aria of Sorrow (USA) (Castlevania Advance Collection).gba" size 8388608 crc 66666666 serial "A2CE" )
)

game (
	name "Final Fantasy VI Advance (USA)"
	region "USA"
	serial "BZ6E"
	rom ( name "Final Fantasy VI Advance (USA).gba" size 8388608 crc D708E622 serial "BZ6E" )
)

game (
	name "Pokemon - Emerald Version (USA, Europe)"
	region "USA"
	serial "BPEE"
	rom ( name "Pokemon - Emerald Version (USA, Europe).gba" size 16777216 crc 1F1C08FB serial "BPEE" )
)

game (
	name "Metroid - Zero Mission (USA)"
	region "USA"
	serial "BMXE"
	rom ( name "Metroid - Zero Mission (USA).gba" size 8388608 crc 5C61A840 serial "BMXE" )
)

game (
	name "Apotris (World) (v3.4.5) (Aftermarket) (Unl)"
	region "World"
	rom ( name "Apotris (World) (v3.4.5) (Aftermarket) (Unl).gba" size 8388608 crc A0030405 )
)

game (
	name "Apotris (World) (v4.0.2) (Aftermarket) (Unl)"
	region "World"
	rom ( name "Apotris (World) (v4.0.2) (Aftermarket) (Unl).gba" size 8388608 crc A0040002 )
)

game (
	name "Apotris (World) (v4.1.0) (Aftermarket) (Unl)"
	region "World"
	rom ( name "Apotris (World) (v4.1.0) (Aftermarket) (Unl).gba" size 8388608 crc A0040100 )
)
`;

const GB_DAT = `clrmamepro (
	name "Nintendo - Game Boy"
	description "Nintendo - Game Boy"
)

game (
	name "Kaeru no Tame ni Kane wa Naru (Japan)"
	region "Japan"
	rom ( name "Kaeru no Tame ni Kane wa Naru (Japan).gb" size 524288 crc 81DF7B1C )
)

game (
	name "Tetris (World)"
	region "World"
	rom ( name "Tetris (World).gb" size 32768 crc 11AA22BB )
)

game (
	name "Tetris (World) (Rev 1)"
	region "World"
	rom ( name "Tetris (World) (Rev 1).gb" size 32768 crc 33CC44DD )
)
`;

const GBC_DAT = `clrmamepro (
	name "Nintendo - Game Boy Color"
	description "Nintendo - Game Boy Color"
)

game (
	name "Pokemon - Crystal Version (USA, Europe)"
	region "USA"
	rom ( name "Pokemon - Crystal Version (USA, Europe).gbc" size 2097152 crc 9F2922B2 )
)

game (
	name "Pokemon - Crystal Version (USA, Europe) (Rev 1)"
	region "USA"
	rom ( name "Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc" size 2097152 crc 301899B8 )
)

game (
	name "Legend of Zelda, The - Oracle of Ages (USA, Australia)"
	region "USA"
	rom ( name "Legend of Zelda, The - Oracle of Ages (USA, Australia).gbc" size 1048576 crc C4639CC6 )
)
`;

const ART_INDEX = {
  '20DF8996': { wheel: 'GBA/wheel/20DF8996.png', 'support-texture': 'GBA/tex/20DF8996.png' },
  '91DC2E30': { wheel: 'GBA/wheel/91DC2E30.png', 'support-texture': 'GBA/tex/91DC2E30.png' },
  '7D9F3101': { wheel: 'GBA/wheel/7D9F3101.png', 'support-texture': 'GBA/tex/7D9F3101.png' },
  '11111111': { wheel: 'GBA/wheel/11111111.png', 'support-texture': 'GBA/tex/11111111.png' },
  '26E16D72': { wheel: 'GBA/wheel/26E16D72.png', 'support-texture': 'GBA/tex/26E16D72.png' },
  '22222222': { wheel: 'GBA/wheel/22222222.png', 'support-texture': 'GBA/tex/22222222.png' },
  '33333333': { wheel: 'GBA/wheel/33333333.png', 'support-texture': 'GBA/tex/33333333.png' },
  '44444444': { wheel: 'GBA/wheel/44444444.png', 'support-texture': 'GBA/tex/44444444.png' },
  '88DAB22D': { wheel: 'GBA/wheel/88DAB22D.png', 'support-texture': 'GBA/tex/88DAB22D.png' },
  '55555555': { wheel: 'GBA/wheel/55555555.png', 'support-texture': 'GBA/tex/55555555.png' },
  '506708D9': { wheel: 'GBA/wheel/506708D9.png', 'support-texture': 'GBA/tex/506708D9.png' },
  '66666666': { wheel: 'GBA/wheel/66666666.png', 'support-texture': 'GBA/tex/66666666.png' },
  D708E622: { wheel: 'GBA/wheel/D708E622.png', 'support-texture': 'GBA/tex/D708E622.png' },
  '1F1C08FB': { wheel: 'GBA/wheel/1F1C08FB.png', 'support-texture': 'GBA/tex/1F1C08FB.png' },
  '5C61A840': { wheel: 'GBA/wheel/5C61A840.png', 'support-texture': 'GBA/tex/5C61A840.png' },
  A0030405: { wheel: 'GBA/wheel/A0030405.png' },
  A0040002: { wheel: 'GBA/wheel/A0040002.png' },
  A0040100: { wheel: 'GBA/wheel/A0040100.png' },
  '81DF7B1C': { wheel: 'GB/wheel/81DF7B1C.png', 'support-texture': 'GB/tex/81DF7B1C.png' },
  '11AA22BB': { wheel: 'GB/wheel/11AA22BB.png' },
  '33CC44DD': { wheel: 'GB/wheel/33CC44DD.png' },
  '9F2922B2': { wheel: 'GBC/wheel/9F2922B2.png', 'support-texture': 'GBC/tex/9F2922B2.png' },
  '301899B8': { wheel: 'GBC/wheel/301899B8.png', 'support-texture': 'GBC/tex/301899B8.png' },
  C4639CC6: { wheel: 'GBC/wheel/C4639CC6.png', 'support-texture': 'GBC/tex/C4639CC6.png' },
};

const hasArtwork = (hex) => ART_INDEX[hex] ?? null;

test('matches translated, patched and homebrew ROM corpus across GBA, GB and GBC', () => {
  const gba = parseCatalog(GBA_DAT, 'GBA');
  const gb = parseCatalog(GB_DAT, 'GB');
  const gbc = parseCatalog(GBC_DAT, 'GBC');

  const cases = [
    {
      catalog: gba,
      cart: { platform: 'GBA', stem: 'Mother 3 (Japan) [T-En by fan v1.3]', code: 'A3UJ' },
      want: { game: 'Mother 3 (Japan)', crc: 0x20df8996 },
    },
    {
      catalog: gba,
      cart: { platform: 'GBA', stem: 'Rhythm Tengoku (Japan) (Translated En)', code: 'BRIJ' },
      want: { game: 'Rhythm Tengoku (Japan)', crc: 0x91dc2e30 },
    },
    {
      catalog: gba,
      cart: { platform: 'GBA', stem: 'WarioWare - Twisted! (USA) [No-Tilt Patch]', code: 'RZWE' },
      want: { game: 'WarioWare - Twisted! (USA)', crc: 0x7d9f3101 },
    },
    {
      catalog: gba,
      cart: { platform: 'GBA', stem: 'Advance Wars (USA) (Rev 1) [RetroAchievements]', code: 'AWRE' },
      want: { game: 'Advance Wars (USA) (Rev 1)', crc: 0x26e16d72 },
    },
    {
      catalog: gba,
      cart: { platform: 'GBA', stem: 'Super Mario Advance 4 - Super Mario Bros. 3 (USA) (Rev 1) (e-Reader)', code: 'AX4E' },
      want: { game: 'Super Mario Advance 4 - Super Mario Bros. 3 (USA, Australia) (Rev 1)', crc: 0x88dab22d },
    },
    {
      catalog: gba,
      cart: { platform: 'GBA', stem: 'Castlevania - Aria of Sorrow (USA) [Rebalance]', code: 'A2CE' },
      want: { game: 'Castlevania - Aria of Sorrow (USA)', crc: 0x506708d9 },
    },
    {
      catalog: gba,
      cart: { platform: 'GBA', stem: 'Final Fantasy VI Advance (USA) [Sound Restoration]', code: 'BZ6E' },
      want: { game: 'Final Fantasy VI Advance (USA)', crc: 0xd708e622 },
    },
    {
      catalog: gba,
      cart: { platform: 'GBA', stem: 'Apotris (World) (Aftermarket) (Unl)', code: '' },
      want: { game: 'Apotris (World) (v4.1.0) (Aftermarket) (Unl)', crc: 0xa0040100 },
    },
    {
      catalog: gb,
      cart: { platform: 'GB', stem: 'For the Frog the Bell Tolls (Japan) [T-En]', code: '' },
      want: { game: 'Kaeru no Tame ni Kane wa Naru (Japan)', crc: 0x81df7b1c },
    },
    {
      catalog: gbc,
      cart: { platform: 'GBC', stem: 'Pokemon - Crystal Version (USA, Europe) (Rev 1) [RTC Patch]', code: '' },
      want: { game: 'Pokemon - Crystal Version (USA, Europe) (Rev 1)', crc: 0x301899b8 },
    },
  ];

  for (const { catalog, cart, want } of cases) {
    assert.deepEqual(matchFromCatalog(catalog, cart, hasArtwork), want, cart.stem);
  }
});

test('never substitutes artwork from a different region when the ROM filename specifies a region', () => {
  const gba = parseCatalog(GBA_DAT, 'GBA');
  assert.equal(
    matchFromCatalog(gba, { platform: 'GBA', stem: 'Mother 3 (USA) [T-En]', code: 'A3UJ' }, hasArtwork),
    null,
  );
  assert.equal(
    matchFromCatalog(gba, { platform: 'GBA', stem: 'Metroid - Zero Mission (Japan) [Hack]', code: 'BMXE' }, hasArtwork),
    null,
  );
});

test('prefers physical releases over Virtual Console, Switch Online and collection dumps unless requested', () => {
  const gba = parseCatalog(GBA_DAT, 'GBA');
  assert.deepEqual(
    matchFromCatalog(gba, { platform: 'GBA', stem: 'Advance Wars (USA) [Patched]', code: 'AWRE' }, hasArtwork),
    { game: 'Advance Wars (USA)', crc: 0x11111111 },
  );
  assert.deepEqual(
    matchFromCatalog(gba, { platform: 'GBA', stem: 'Advance Wars (USA) (Virtual Console) [Patched]', code: 'AWRE' }, hasArtwork),
    { game: 'Advance Wars (USA) (Virtual Console)', crc: 0x22222222 },
  );
  assert.deepEqual(
    matchFromCatalog(gba, { platform: 'GBA', stem: 'Super Mario Advance 4 - Super Mario Bros. 3 (USA) (Switch Online) [Mod]', code: 'AX4E' }, hasArtwork),
    { game: 'Super Mario Advance 4 - Super Mario Bros. 3 (USA) (Switch Online)', crc: 0x55555555 },
  );
});

test('rejects candidates whose 4-char GBA game code family conflicts with the ROM header', () => {
  const gba = parseCatalog(GBA_DAT, 'GBA');
  assert.equal(
    matchFromCatalog(gba, { platform: 'GBA', stem: 'Advance Wars (USA) [Patched]', code: 'BPEE' }, hasArtwork),
    null,
  );
});

test('allows bounded fuzzy title matching only when a 4-char GBA game code matches exactly', () => {
  const gba = parseCatalog(GBA_DAT, 'GBA');
  assert.deepEqual(
    matchFromCatalog(gba, { platform: 'GBA', stem: 'Metroid - Zero Mision (USA) [Improvement]', code: 'BMXE' }, hasArtwork),
    { game: 'Metroid - Zero Mission (USA)', crc: 0x5c61a840 },
  );
  assert.equal(
    matchFromCatalog(gba, { platform: 'GBA', stem: 'Metroid - Zero Mision (USA) [Improvement]', code: '' }, hasArtwork),
    null,
  );
});

test('picks an exact version when specified and returns null when multiple distinct candidates tie', () => {
  const gba = parseCatalog(GBA_DAT, 'GBA');
  assert.deepEqual(
    matchFromCatalog(gba, { platform: 'GBA', stem: 'Apotris (World) (v3.4.5) (Aftermarket)', code: '' }, hasArtwork),
    { game: 'Apotris (World) (v3.4.5) (Aftermarket) (Unl)', crc: 0xa0030405 },
  );
  // Advance Wars without region or code has both USA and Europe candidates with different artwork.
  assert.equal(
    matchFromCatalog(gba, { platform: 'GBA', stem: 'Advance Wars [Patched]', code: '' }, hasArtwork),
    null,
  );
  // But if both tied candidates share identical artwork paths, the shared match succeeds.
  assert.deepEqual(
    matchFromCatalog(gba, { platform: 'GBA', stem: 'Advance Wars [Patched]', code: '' }, () => 'GBA/wheel/shared.png'),
    { game: 'Advance Wars (USA)', crc: 0x11111111 },
  );
});

test('strips parenthetical and bracketed tags for editor search queries while keeping subtitles', () => {
  assert.equal(
    searchQueryOf('Super Mario Advance 4 - Super Mario Bros. 3 (USA) (Rev 1) [T-Zh]'),
    'Super Mario Advance 4 - Super Mario Bros. 3',
  );
  assert.equal(searchQueryOf('Mother 3 (Japan) [English v1.3]'), 'Mother 3');
  assert.equal(searchQueryOf('[BIOS] Game Boy Advance (World)'), 'Game Boy Advance');
  assert.equal(searchQueryOf('( Prototype )'), 'Prototype');
});

test('normalizes diacritics, punctuation and tags consistently', () => {
  assert.equal(normalizeTitle('Pokémon - Émeraude (France) [T-En]'), 'pokemon emeraude');
  assert.deepEqual(extractBracketTags('Game (USA, Europe) (Rev 1) [Hack]'), ['USA, Europe', 'Rev 1', 'Hack']);
  const tags = parseTags('Super Mario Advance 4 (USA, Australia) (Rev 1) (Switch Online) (v1.2.3)');
  assert.deepEqual(tags.regions, ['USA', 'Australia']);
  assert.equal(tags.revision, '1');
  assert.deepEqual(tags.version, [1, 2, 3]);
  assert.deepEqual(tags.digitalEditions, ['switch online']);
  assert.deepEqual(parseTags('007 - Everything or Nothing(UE)(M3)(64Mb)').regions, ['USA', 'Europe']);
  assert.deepEqual(parseTags('Breath of Fire(US)(32Mb)').regions, ['USA']);
});
