// Passwords (bcrypt), access tokens (JWT, HS256) and refresh tokens (opaque, hashed at rest).
import { randomBytes } from "node:crypto";
import bcrypt from "bcryptjs";
import type { Clock } from "./clock.js";
import { type Principal, type User } from "./models.js";

export const ISSUER = "devblog";
export const ACCESS_TOKEN_TTL_SECONDS = 15 * 60;
export const REFRESH_TOKEN_TTL_MS = 7 * 24 * 3600 * 1000;
export const MIN_SECRET_BYTES = 32;

/** bcrypt with a configurable cost (10 in production, 4 in tests to keep them fast). */
export class PasswordHasher {
  private readonly dummy: string;

  constructor(private readonly cost = 10) {
    if (!Number.isInteger(cost) || cost < 4 || cost > 15) throw new Error("BCRYPT_COST must be 4-15");
    // Used when the e-mail is unknown, so a login takes the same time either way.
    this.dummy = bcrypt.hashSync("not-a-real-password", cost);
  }

  hash(password: string): Promise<string> {
    // TODO(phase-3 M2): bcrypt with the configured cost (salt included in the hash).
    throw new Error("TODO(phase-3 M2): PasswordHasher.hash");
  }

  async verify(password: string, passwordHash: string | null): Promise<boolean> {
    // TODO(phase-3 M2): bcrypt check; never throw on a malformed hash, return false.
    throw new Error("TODO(phase-3 M2): PasswordHasher.verify");
  }
}

export function resolveSecret(configured: string | null, warn: (msg: string) => void = console.warn): string {
  if (configured === null) {
    warn("JWT_SECRET is not set: using a random secret, tokens die with this process (dev only)");
    return randomBytes(48).toString("base64url");
  }
  if (Buffer.byteLength(configured, "utf8") < MIN_SECRET_BYTES) {
    throw new Error(`JWT_SECRET must be at least ${MIN_SECRET_BYTES} bytes`);
  }
  return configured;
}

export class TokenService {
  private readonly key: Uint8Array;

  constructor(
    secret: string,
    private readonly clock: Clock,
  ) {
    this.key = new TextEncoder().encode(secret);
  }

  issueAccessToken(user: User): Promise<string> {
    // TODO(phase-3 M2): HS256 JWT with sub (user id as string), role, iss 'devblog', iat, exp = now + 900
    // s, using the injected clock.
    throw new Error("TODO(phase-3 M2): TokenService.issueAccessToken");
  }

  async verifyAccessToken(token: string): Promise<Principal> {
    // TODO(phase-3 M2): pin the algorithm to HS256, check signature, iss and exp against the injected
    // clock; any problem -> 401 unauthorized. Return Principal(user id, role).
    throw new Error("TODO(phase-3 M2): TokenService.verifyAccessToken");
  }
}

export function hashRefreshToken(token: string): string {
  // TODO(phase-3 M2): SHA-256 hex digest. Only the hash is stored (like a password, but fast is fine: it
  // has 256 bits of entropy).
  throw new Error("TODO(phase-3 M2): hashRefreshToken");
}

/** Returns the token for the client and its SHA-256 hex digest for the database. */
export function newRefreshToken(): { token: string; hash: string } {
  // TODO(phase-3 M2): 32 random bytes from a CSPRNG, base64url without padding. Opaque: not a JWT.
  throw new Error("TODO(phase-3 M2): newRefreshToken");
}
