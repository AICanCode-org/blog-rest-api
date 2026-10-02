// Slugs, excerpts and keyset cursors (pure functions, identical rules in every track).

export const SLUG_MAX = 80;
export const EXCERPT_MAX = 160;
export const CURSOR = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z)\|([0-9]{1,18})$/;

/** 'Café & Code: Part 2!' -> 'cafe-code-part-2'. Empty results become 'post'. */
export function slugify(title: string): string {
  // TODO(phase-3 M1): NFKD-normalise, drop non-ASCII, lower-case, runs of anything but [a-z0-9] -> one
  // '-', trim '-', cut to 80 and trim '-' again; empty -> 'post'.
  throw new Error("TODO(phase-3 M1): slugify");
}

/** Drop the most common markdown symbols and collapse whitespace. */
export function plainText(markdown: string): string {
  // TODO(phase-3 M1): drop the characters # * ` > and collapse whitespace (then trim). Used by excerpts
  // and summaries.
  throw new Error("TODO(phase-3 M1): plainText");
}

/** Lengths are in characters (code points), so an emoji counts as one, like in the other tracks. */
export function shorten(text: string, maxLen: number): string {
  // TODO(phase-3 M1): at most `limit` characters (code points): if longer, cut to limit-3 (no trailing
  // space) and add '...'.
  throw new Error("TODO(phase-3 M1): shorten");
}

export function excerpt(body: string): string {
  // TODO(phase-3 M1): shorten(plain_text(body), 160).
  throw new Error("TODO(phase-3 M1): excerpt");
}

export function encodeCursor(publishedAt: Date, id: number): string {
  // TODO(phase-3 M1): base64url WITHOUT padding of 'YYYY-MM-DDTHH:MM:SS.sssZ|id' (milliseconds, UTC).
  // ADR-0002.
  throw new Error("TODO(phase-3 M1): encodeCursor");
}

export function decodeCursor(cursor: string | undefined | null): { publishedAt: Date; id: number } | null {
  // TODO(phase-3 M1): missing/empty -> null; anything that does not decode to exactly that format -> 400
  // 'cursor is invalid'.
  throw new Error("TODO(phase-3 M1): decodeCursor");
}
