// Input validation. Every function returns the normalised value or throws a 400 problem.
// The limits are the ones in spec/requirements.md and spec/openapi.yaml; the Java and Python
// tracks implement the same rules with the same messages.
import { notFound, validationError } from "./errors.js";
import { ROLES, type CommentStatus, type PostStatus, type Role } from "./models.js";

const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const TAG_RE = /^[a-z0-9]+(-[a-z0-9]+)*$/;
const DIGITS_RE = /^[0-9]+$/;
export const MAX_TAGS = 5;
export const MAX_BODY = 50_000;
export const MAX_COMMENT = 2_000;
export const DEFAULT_LIMIT = 10;
export const MAX_LIMIT = 50;

const isPositiveInt = (v: unknown): v is number => typeof v === "number" && Number.isSafeInteger(v) && v >= 1;
const utf8Bytes = (s: string) => Buffer.byteLength(s, "utf8");

export type Json = Record<string, unknown>;

export function requireObject(payload: unknown): Json {
  if (typeof payload !== "object" || payload === null || Array.isArray(payload)) {
    throw validationError("request body must be a JSON object");
  }
  return payload as Json;
}

export function email(value: unknown): string {
  if (typeof value !== "string") throw validationError("email is required");
  const normalised = value.trim().toLowerCase();
  if (normalised.length > 254 || !EMAIL_RE.test(normalised)) {
    throw validationError("email must be a valid e-mail address");
  }
  return normalised;
}

export function password(value: unknown): string {
  // Length in characters (code points) for the minimum, bytes for the maximum: bcrypt reads 72 bytes.
  if (typeof value !== "string" || [...value].length < 8 || utf8Bytes(value) > 72) {
    throw validationError("password must be 8 characters to 72 bytes");
  }
  return value;
}

const charLength = (s: string) => [...s].length;

export function displayName(value: unknown): string {
  if (typeof value !== "string" || charLength(value.trim()) < 1 || charLength(value.trim()) > 50) {
    throw validationError("displayName must be 1-50 characters");
  }
  return value.trim();
}

export function role(value: unknown): Role {
  if (!ROLES.includes(value as Role)) throw validationError("role must be one of READER, AUTHOR, ADMIN");
  return value as Role;
}

export function title(value: unknown): string {
  if (typeof value !== "string" || charLength(value.trim()) < 1 || charLength(value.trim()) > 200) {
    throw validationError("title must be 1-200 characters");
  }
  return value.trim();
}

export function body(value: unknown): string {
  if (typeof value !== "string" || !value.trim() || charLength(value) > MAX_BODY) {
    throw validationError("body must be 1-50000 characters");
  }
  return value;
}

export function tags(value: unknown): string[] {
  if (value === undefined || value === null) return [];
  if (!Array.isArray(value) || !value.every((t) => typeof t === "string")) {
    throw validationError("tags must be an array of strings");
  }
  const cleaned = [...new Set(value.map((t: string) => t.trim().toLowerCase()))].sort();
  if (cleaned.length > MAX_TAGS) throw validationError("at most 5 tags");
  for (const t of cleaned) {
    if (t.length > 30 || !TAG_RE.test(t)) {
      throw validationError("tags must be lower-case letters, digits and single hyphens (max 30)");
    }
  }
  return cleaned;
}

/** In a create, a missing status means "draft". In a PATCH (`fallback` null), an explicit null is an error. */
export function status(value: unknown, fallback: PostStatus | null = "draft"): PostStatus {
  if ((value === undefined || value === null) && fallback !== null) return fallback;
  if (value !== "draft" && value !== "published") throw validationError("status must be draft or published");
  return value;
}

export function version(value: unknown): number {
  if (!isPositiveInt(value)) throw validationError("version is required (the version you read)");
  return value;
}

export function commentBody(value: unknown): string {
  if (typeof value !== "string" || charLength(value.trim()) < 1 || charLength(value.trim()) > MAX_COMMENT) {
    throw validationError("body must be 1-2000 characters");
  }
  return value.trim();
}

export function parentId(value: unknown): number | null {
  if (value === undefined || value === null) return null;
  if (!isPositiveInt(value)) throw validationError("parentId must be a positive integer");
  return value;
}

export function limit(value: string | undefined): number {
  if (value === undefined) return DEFAULT_LIMIT;
  if (!DIGITS_RE.test(value) || Number(value) < 1 || Number(value) > MAX_LIMIT) {
    throw validationError("limit must be an integer 1-50");
  }
  return Number(value);
}

export function tagFilter(value: string | undefined): string | null {
  if (value === undefined || value === "") return null;
  const t = value.trim().toLowerCase();
  if (t.length > 30 || !TAG_RE.test(t)) {
    throw validationError("tag must be lower-case letters, digits and single hyphens (max 30)");
  }
  return t;
}

export function searchQuery(value: string | undefined): string | null {
  if (value === undefined || !value.trim()) return null;
  if (charLength(value.trim()) > 100) throw validationError("q must be at most 100 characters");
  return value.trim();
}

export function commentStatus(value: string | undefined): CommentStatus {
  if (value === undefined) return "held";
  if (value !== "held" && value !== "visible" && value !== "rejected") {
    throw validationError("status must be held, visible or rejected");
  }
  return value;
}

export function decision(value: unknown): "approve" | "reject" {
  if (value !== "approve" && value !== "reject") throw validationError("decision must be approve or reject");
  return value;
}

/** A non-numeric id in the URL cannot exist, so it is a 404 rather than a 400. */
export function pathId(value: string, what: string): number {
  if (!DIGITS_RE.test(value) || value.length > 15) throw notFound(what);
  return Number(value);
}
