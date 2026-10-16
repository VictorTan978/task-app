import {
  checkTagName,
  findTag,
  hasTag,
  normaliseTagName,
  suggestTags,
  TAG_INVALID_CHARACTERS_MESSAGE,
  TAG_TOO_LONG_MESSAGE,
  tagKey,
} from './task-tags';

describe('task-tags', () => {
  it('normaliseTagName trims and collapses whitespace, including no-break spaces', () => {
    expect(normaliseTagName('  Home   office ')).toBe('Home office');
    expect(normaliseTagName(' Home  office ')).toBe('Home office');
    expect(normaliseTagName('   ')).toBe('');
  });

  it('tagKey ignores case, accents and spacing', () => {
    expect(tagKey('Work')).toBe('work');
    expect(tagKey(' WÖRK ')).toBe('work');
    expect(tagKey('Café  Crème')).toBe('cafe creme');
    expect(tagKey('🚀 Launch')).toBe('🚀 launch');
  });

  describe('checkTagName', () => {
    it('ignores blank input', () => {
      expect(checkTagName('')).toEqual({ kind: 'blank' });
      expect(checkTagName('   ')).toEqual({ kind: 'blank' });
    });

    it.each(['a,b', ',', 'tab\there', 'new\nline', '\u0007bell'])(
      'rejects commas and control characters: %j',
      (raw) => {
        expect(checkTagName(raw)).toEqual({
          kind: 'invalid',
          message: TAG_INVALID_CHARACTERS_MESSAGE,
        });
      },
    );

    it('counts 30 UTF-16 units after normalising', () => {
      expect(checkTagName('  ' + 'x'.repeat(30) + '  ')).toEqual({
        kind: 'ok',
        name: 'x'.repeat(30),
      });
      expect(checkTagName('x'.repeat(31))).toEqual({
        kind: 'invalid',
        message: TAG_TOO_LONG_MESSAGE,
      });
      // 15 emoji = 30 UTF-16 units (ok), 16 = 32 (too long).
      expect(checkTagName('😀'.repeat(15)).kind).toBe('ok');
      expect(checkTagName('😀'.repeat(16)).kind).toBe('invalid');
      expect(checkTagName('a' + ' '.repeat(10) + 'b')).toEqual({ kind: 'ok', name: 'a b' });
    });

    it('keeps emoji and accents', () => {
      expect(checkTagName(' Wörk 🚀 ')).toEqual({ kind: 'ok', name: 'Wörk 🚀' });
    });
  });

  it('hasTag and findTag match ignoring case and accents', () => {
    expect(hasTag(['Work', 'Home'], 'wörk')).toBe(true);
    expect(hasTag(['Work'], 'Works')).toBe(false);
    expect(findTag(['Home', 'Work'], 'WORK')).toBe('Work');
    expect(findTag(['Home'], 'Work')).toBeNull();
  });

  describe('suggestTags', () => {
    const known = ['Errands', 'Home', 'Homework', 'Network', 'Work'];

    it('matches by key, prefix matches first, hides chosen tags', () => {
      expect(suggestTags(known, [], 'wo')).toEqual(['Work', 'Homework', 'Network']);
      expect(suggestTags(known, ['work'], 'WÖ')).toEqual(['Homework', 'Network']);
      expect(suggestTags(known, [], 'xyz')).toEqual([]);
    });

    it('empty text suggests everything not chosen, up to the limit', () => {
      expect(suggestTags(known, ['Home'], '')).toEqual(['Errands', 'Homework', 'Network', 'Work']);
      expect(suggestTags(known, [], '', 2)).toEqual(['Errands', 'Home']);
    });
  });
});
