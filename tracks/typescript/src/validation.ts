// Input validation. Every function returns the normalised value or throws a 400 problem.
// The limits are the ones in spec/requirements.md and spec/openapi.yaml; the Java and Python
// tracks implement the same rules with the same messages.
import { validationError } from "./errors.js";
import { type CommentStatus, type PostStatus, type Role } from "./models.js";

export const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
export const TAG_RE = /^[a-z0-9]+(-[a-z0-9]+)*$/;
export const DIGITS_RE = /^[0-9]+$/;
export const MAX_TAGS = 5;
export const MAX_BODY = 50_000;
export const MAX_COMMENT = 2_000;
export const DEFAULT_LIMIT = 10;
export const MAX_LIMIT = 50;

export const isPositiveInt = (v: unknown): v is number =>
  typeof v === "number" && Number.isSafeInteger(v) && v >= 1;
export const utf8Bytes = (s: string) => Buffer.byteLength(s, "utf8");

export type Json = Record<string, unknown>;

export function requireObject(payload: unknown): Json {
  if (typeof payload !== "object" || payload === null || Array.isArray(payload)) {
    throw validationError("request body must be a JSON object");
  }
  return payload as Json;
}

export function email(value: unknown): string {
  // TODO(phase-3 M1): string, trimmed and lower-cased, at most 254 characters, must match EMAIL_RE.
  // Exact messages: see the validation tests.
  throw new Error("TODO(phase-3 M1): email");
}

export function password(value: unknown): string {
  // TODO(phase-3 M1): string of at least 8 characters and at most 72 BYTES in UTF-8 (bcrypt ignores the
  // rest). Do not trim.
  throw new Error("TODO(phase-3 M1): password");
}

export const charLength = (s: string) => [...s].length;

export function displayName(value: unknown): string {
  // TODO(phase-3 M1): string, trimmed, 1..50 characters (code points, not UTF-16 units).
  throw new Error("TODO(phase-3 M1): displayName");
}

export function role(value: unknown): Role {
  // TODO(phase-3 M1): one of READER, AUTHOR, ADMIN (exact case).
  throw new Error("TODO(phase-3 M1): role");
}

export function title(value: unknown): string {
  // TODO(phase-3 M1): string, trimmed, 1..200 characters.
  throw new Error("TODO(phase-3 M1): title");
}

export function body(value: unknown): string {
  // TODO(phase-3 M1): string, not blank, at most 50 000 characters. Stored as-is (Markdown): no
  // trimming.
  throw new Error("TODO(phase-3 M1): body");
}

export function tags(value: unknown): string[] {
  // TODO(phase-3 M1): missing/null -> []. A list of strings; trim + lower-case each, de-duplicate and sort,
  //   then at most 5 and each must match TAG_RE with at most 30 characters.
  throw new Error("TODO(phase-3 M1): tags");
}

/** In a create, a missing status means "draft". In a PATCH (`fallback` null), an explicit null is an error. */
export function status(value: unknown, fallback: PostStatus | null = "draft"): PostStatus {
  // TODO(phase-3 M1): missing/null -> the default ('draft' on create); otherwise 'draft' or 'published'.
  throw new Error("TODO(phase-3 M1): status");
}

export function version(value: unknown): number {
  // TODO(phase-3 M1): a positive integer (booleans are not integers!). Required on PATCH.
  throw new Error("TODO(phase-3 M1): version");
}

export function commentBody(value: unknown): string {
  // TODO(phase-3 M1): string, trimmed, 1..2000 characters.
  throw new Error("TODO(phase-3 M1): commentBody");
}

export function parentId(value: unknown): number | null {
  // TODO(phase-3 M1): missing/null -> null; otherwise a positive integer.
  throw new Error("TODO(phase-3 M1): parentId");
}

export function limit(value: string | undefined): number {
  // TODO(phase-3 M1): query string: missing -> 10; ASCII digits only ([0-9]+: isdigit() also accepts
  // other scripts), 1..50.
  throw new Error("TODO(phase-3 M1): limit");
}

export function tagFilter(value: string | undefined): string | null {
  // TODO(phase-3 M1): query string: missing/empty -> null; otherwise lower-cased and must match TAG_RE.
  throw new Error("TODO(phase-3 M1): tagFilter");
}

export function searchQuery(value: string | undefined): string | null {
  // TODO(phase-3 M1): query string: trimmed; empty -> null; at most 100 characters.
  throw new Error("TODO(phase-3 M1): searchQuery");
}

export function commentStatus(value: string | undefined): CommentStatus {
  // TODO(phase-3 M1): query string: missing -> 'held'; otherwise held, visible or rejected.
  throw new Error("TODO(phase-3 M1): commentStatus");
}

export function decision(value: unknown): "approve" | "reject" {
  // TODO(phase-3 M1): 'approve' or 'reject'.
  throw new Error("TODO(phase-3 M1): decision");
}

/** A non-numeric id in the URL cannot exist, so it is a 404 rather than a 400. */
export function pathId(value: string, what: string): number {
  // TODO(phase-3 M1): path segment: ASCII digits only (at most 18), otherwise a 404 not-found for
  // `what`: an id that cannot exist is not a 400.
  throw new Error("TODO(phase-3 M1): pathId");
}
