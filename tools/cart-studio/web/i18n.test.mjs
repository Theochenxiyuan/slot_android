import { test } from 'node:test';
import assert from 'node:assert/strict';

Object.defineProperty(globalThis, 'navigator', { value: { languages: ['zh-CN'], language: 'zh-CN' }, configurable: true });
const { t, chinese } = await import('./i18n.js');

test('a Chinese browser reads the studio in Chinese', () => {
  assert.equal(chinese, true);
  assert.equal(t('Slot Manager'), 'Slot Manager');
  assert.equal(t('Couldn’t open Slot Manager. Try again.'), '\u65E0\u6CD5\u6253\u5F00 Slot Manager\uFF0C\u8BF7\u91CD\u8BD5\u3002');
  assert.equal(t('Save'), '\u4FDD\u5B58');
  assert.equal(t('Not saved yet'), '\u5C1A\u672A\u4FDD\u5B58');
  assert.equal(t('Cover not saved yet'), '\u5C01\u9762\u5C1A\u672A\u4FDD\u5B58');
  assert.equal(t('No label'), '\u65E0\u6807\u7B7E');
  assert.equal(t('No cover'), '\u65E0\u5C01\u9762');
  assert.equal(t('Pokémon Emerald'), '\u5B9D\u53EF\u68A6 \u7EFF\u5B9D\u77F3');
  assert.equal(t('Advance Charcoal'), 'GBA \u70AD\u7070');
  assert.equal(t('No matching games. Try searching in English.'), '\u672A\u627E\u5230\u5339\u914D\u6E38\u620F\uFF0C\u8BF7\u5C1D\u8BD5\u7528\u82F1\u6587\u540D\u79F0\u641C\u7D22');
  assert.equal(t('Reset'), '\u91CD\u7F6E');
  assert.equal(t('Keep editing'), '\u7EE7\u7EED\u7F16\u8F91');
  assert.equal(t('Delete game'), '\u5220\u9664\u6E38\u620F');
  assert.equal(t('Delete “Test.gba”? Exclusive labels, covers and shell settings will also be removed. Save files will be kept.'),
    '\u5220\u9664“Test.gba”\uFF1F\u4E13\u5C5E\u6807\u7B7E\u3001\u5C01\u9762\u548C\u5916\u58F3\u914D\u7F6E\u4E5F\u4F1A\u4E00\u5E76\u5220\u9664\uFF0C\u5B58\u6863\u4F1A\u4FDD\u7559\u3002');
  assert.equal(t('Upload games'), '\u4E0A\u4F20\u6E38\u620F');
  assert.equal(t('Reading 3 of 51: Metroid (USA)'), '\u6B63\u5728\u8BFB\u53D6 3/51\uFF1AMetroid (USA)');
  assert.equal(t('2 labels couldn’t be written.'), '2 \u4E2A\u6807\u7B7E\u672A\u80FD\u4FDD\u5B58\u3002');
  assert.equal(t('1 label was skipped: one turned up on the card meanwhile.'), '1 \u4E2A\u6807\u7B7E\u5DF2\u8DF3\u8FC7\uFF1A\u4FDD\u5B58\u671F\u95F4\u638C\u673A\u4E0A\u51FA\u73B0\u4E86\u540C\u540D\u6807\u7B7E\u3002');
  assert.equal(t('12 carts have no label yet'), '12 \u4E2A\u5361\u5E26\u8FD8\u6CA1\u6709\u6807\u7B7E');
  assert.equal(t('8 unlabelled games · 5 can be generated'), '8 \u4E2A\u6E38\u620F\u65E0\u6807\u7B7E · 5 \u4E2A\u53EF\u751F\u6210');
  assert.equal(t('1 unlabelled game'), '1 \u4E2A\u6E38\u620F\u65E0\u6807\u7B7E');
  assert.equal(t('Generate labels (5)'), '\u751F\u6210\u6807\u7B7E\uFF085\uFF09');
  assert.equal(t('3 covers not saved yet'), '3 \u5F20\u5C01\u9762\u5C1A\u672A\u4FDD\u5B58');
  assert.equal(t('1 cover not saved yet'), '1 \u5F20\u5C01\u9762\u5C1A\u672A\u4FDD\u5B58');
  assert.equal(t('2 unlabelled games · 3 covers not saved yet'), '2 \u4E2A\u6E38\u620F\u65E0\u6807\u7B7E · 3 \u5F20\u5C01\u9762\u5C1A\u672A\u4FDD\u5B58');
  assert.equal(t('Save covers (3)'), '\u8865\u5168\u5C01\u9762\uFF083\uFF09');
  assert.equal(t('Fill labels and covers (5)'), '\u8865\u5168\u6807\u7B7E\u4E0E\u5C01\u9762\uFF085\uFF09');
  assert.equal(t('How should the studio fill them? 3 matched covers will also be saved.'), '\u8981\u7528\u54EA\u79CD\u65B9\u5F0F\u751F\u6210\uFF1F\u8FD8\u4F1A\u4E00\u5E76\u4FDD\u5B58 3 \u5F20\u5DF2\u5339\u914D\u7684\u5C01\u9762\u3002');
  assert.equal(t('Saving 2 of 5…'), '\u6B63\u5728\u4FDD\u5B58 2/5…');
  assert.equal(t('Box art'), '\u5C01\u9762');
  assert.equal(t('Custom image'), '\u81EA\u5B9A\u4E49\u56FE\u7247');
  assert.equal(t('cover.heic is not an image this studio can read'), 'cover.heic \u4E0D\u662F\u53EF\u8BFB\u53D6\u7684\u56FE\u7247');
  assert.equal(t('3 box art couldn’t be written.'), '3 \u5F20\u5C01\u9762\u672A\u80FD\u4FDD\u5B58\u3002');
  assert.equal(t('1 box art skipped: one turned up on the card meanwhile.'), '1 \u5F20\u5C01\u9762\u5DF2\u8DF3\u8FC7\uFF1A\u4FDD\u5B58\u671F\u95F4\u638C\u673A\u4E0A\u51FA\u73B0\u4E86\u540C\u540D\u5C01\u9762\u3002');
});

test('names and anything unknown pass through untouched', () => {
  assert.equal(t('Metroid - Zero Mission'), 'Metroid - Zero Mission');
  assert.equal(t(''), '');
  assert.equal(t(null), null);
});
