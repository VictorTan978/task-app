/**
 * Client-side tag rules (contract "Tags"). The server is the authority: these checks only give
 * early feedback and a matching hint (the server's collation decides, e.g. ß = ss). The UI always
 * shows the names the server returns.
 */

export const TAG_MAX_LENGTH = 30;
export const TAGS_PER_TASK_MAX = 10;

export const TAG_INVALID_CHARACTERS_MESSAGE = "Tags can't contain commas or control characters";
export const TAG_TOO_LONG_MESSAGE = 'Tags must be 30 characters or fewer';
export const TAG_TOO_MANY_MESSAGE = 'A task can have at most 10 tags';

/** Comma or a control character (Unicode category Cc), anywhere. */
const INVALID = /[,\p{Cc}]/u;
/** JS `\s` covers the Unicode space separators, incl. U+00A0. */
const WHITESPACE = /\s+/gu;
const MARKS = /\p{M}/gu;

/** Trimmed, every inner run of whitespace collapsed to one space. */
export function normaliseTagName(raw: string): string {
  return raw.replace(WHITESPACE, ' ').trim();
}

/** Matching key (ignoring case and accents). A hint only; never shown. */
export function tagKey(name: string): string {
  return normaliseTagName(name).normalize('NFD').replace(MARKS, '').toLowerCase();
}

export type TagCheck =
  { kind: 'blank' } | { kind: 'invalid'; message: string } | { kind: 'ok'; name: string };

/** Checks one typed tag in the server's order: characters, then blank, then length. */
export function checkTagName(raw: string): TagCheck {
  if (INVALID.test(raw)) {
    return { kind: 'invalid', message: TAG_INVALID_CHARACTERS_MESSAGE };
  }
  const name = normaliseTagName(raw);
  if (!name) {
    return { kind: 'blank' };
  }
  if (name.length > TAG_MAX_LENGTH) {
    return { kind: 'invalid', message: TAG_TOO_LONG_MESSAGE };
  }
  return { kind: 'ok', name };
}

/** True if `tags` contains `name` (ignoring case and accents). */
export function hasTag(tags: readonly string[], name: string): boolean {
  const key = tagKey(name);
  return tags.some((t) => tagKey(t) === key);
}

/** The first of `known` with the same key as `name`, else null. */
export function findTag(known: readonly string[], name: string): string | null {
  const key = tagKey(name);
  return known.find((t) => tagKey(t) === key) ?? null;
}

/**
 * Suggestions for what the user typed: known tags not already chosen whose key contains the typed
 * key, those starting with it first, otherwise in the given (server) order. Empty text matches all.
 */
export function suggestTags(
  known: readonly string[],
  chosen: readonly string[],
  typed: string,
  limit = 8,
): string[] {
  const key = tagKey(typed);
  const chosenKeys = new Set(chosen.map(tagKey));
  const prefix: string[] = [];
  const inner: string[] = [];
  for (const name of known) {
    const k = tagKey(name);
    if (chosenKeys.has(k)) {
      continue;
    }
    if (k.startsWith(key)) {
      prefix.push(name);
    } else if (k.includes(key)) {
      inner.push(name);
    }
  }
  return [...prefix, ...inner].slice(0, limit);
}
