// Slugs, excerpts and keyset cursors (pure functions, identical rules in every track).
import { validationError } from "./errors.js";

export const SLUG_MAX = 80;
export const EXCERPT_MAX = 160;
const CURSOR = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z)\|([0-9]{1,18})$/;

/** 'Café & Code: Part 2!' -> 'cafe-code-part-2'. Empty results become 'post'. */
export function slugify(title: string): string {
  const ascii = title
    .normalize("NFKD")
    .replace(/[^\x00-\x7f]/g, "") // eslint-disable-line no-control-regex
    .toLowerCase();
  let slug = ascii.replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
  slug = slug.slice(0, SLUG_MAX).replace(/-+$/, "");
  return slug || "post";
}

/** Drop the most common markdown symbols and collapse whitespace. */
export const plainText = (markdown: string) =>
  markdown
    .replace(/[#*`>]/g, "")
    .replace(/\s+/g, " ")
    .trim();

/** Lengths are in characters (code points), so an emoji counts as one, like in the other tracks. */
export function shorten(text: string, maxLen: number): string {
  const chars = [...text];
  if (chars.length <= maxLen) return text;
  return (
    chars
      .slice(0, maxLen - 3)
      .join("")
      .trimEnd() + "..."
  );
}

export const excerpt = (body: string) => shorten(plainText(body), EXCERPT_MAX);

export function encodeCursor(publishedAt: Date, id: number): string {
  return Buffer.from(`${publishedAt.toISOString()}|${id}`, "utf8").toString("base64url");
}

export function decodeCursor(cursor: string | undefined | null): { publishedAt: Date; id: number } | null {
  if (cursor === undefined || cursor === null || cursor === "") return null;
  const raw = /^[A-Za-z0-9_-]+$/.test(cursor) ? Buffer.from(cursor, "base64url").toString("utf8") : "";
  const match = CURSOR.exec(raw);
  const publishedAt = match ? new Date(match[1]!) : null;
  // new Date("2026-13-02...") is an Invalid Date; toISOString() round-trips only for real dates.
  if (
    !match ||
    !publishedAt ||
    Number.isNaN(publishedAt.getTime()) ||
    publishedAt.toISOString() !== match[1]
  ) {
    throw validationError("cursor is invalid");
  }
  return { publishedAt, id: Number(match[2]) };
}
