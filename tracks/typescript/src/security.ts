// Passwords (bcrypt), access tokens (JWT, HS256) and refresh tokens (opaque, hashed at rest).
import { createHash, randomBytes } from "node:crypto";
import bcrypt from "bcryptjs";
import { jwtVerify, SignJWT } from "jose";
import type { Clock } from "./clock.js";
import { unauthorized } from "./errors.js";
import { ROLES, type Principal, type Role, type User } from "./models.js";

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
    return bcrypt.hash(password, this.cost);
  }

  async verify(password: string, passwordHash: string | null): Promise<boolean> {
    const ok = await bcrypt.compare(password, passwordHash ?? this.dummy);
    return ok && passwordHash !== null;
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
    const now = Math.floor(this.clock().getTime() / 1000);
    return new SignJWT({ role: user.role })
      .setProtectedHeader({ alg: "HS256", typ: "JWT" })
      .setSubject(String(user.id))
      .setIssuer(ISSUER)
      .setIssuedAt(now)
      .setExpirationTime(now + ACCESS_TOKEN_TTL_SECONDS)
      .sign(this.key);
  }

  async verifyAccessToken(token: string): Promise<Principal> {
    let claims;
    try {
      // algorithms: ["HS256"] pins the algorithm: "alg": "none" or RS256 tokens are rejected.
      // currentDate makes jose check exp against the injected clock, so tests can travel in time.
      ({ payload: claims } = await jwtVerify(token, this.key, {
        algorithms: ["HS256"],
        issuer: ISSUER,
        currentDate: this.clock(),
        requiredClaims: ["exp", "sub"],
      }));
    } catch {
      throw unauthorized("invalid or expired token");
    }
    const { exp, sub, role } = claims;
    if (typeof exp !== "number" || exp * 1000 <= this.clock().getTime()) {
      throw unauthorized("invalid or expired token");
    }
    if (typeof sub !== "string" || !/^[0-9]+$/.test(sub) || !ROLES.includes(role as Role)) {
      throw unauthorized("invalid or expired token");
    }
    return { userId: Number(sub), role: role as Role };
  }
}

export const hashRefreshToken = (token: string) => createHash("sha256").update(token, "utf8").digest("hex");

/** Returns the token for the client and its SHA-256 hex digest for the database. */
export function newRefreshToken(): { token: string; hash: string } {
  const token = randomBytes(32).toString("base64url");
  return { token, hash: hashRefreshToken(token) };
}
