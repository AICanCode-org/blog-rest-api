// Accounts, login, token rotation and roles. Framework-free: the HTTP layer only parses and formats.
import type { Clock } from "./clock.js";
import {
  DuplicateError,
  emailTaken,
  forbidden,
  invalidCredentials,
  invalidRefreshToken,
  notFound,
  unauthorized,
  validationError,
} from "./errors.js";
import type { Principal, Role, TokenPair, User } from "./models.js";
import type { UserRepository } from "./repository.js";
import {
  hashRefreshToken,
  newRefreshToken,
  PasswordHasher,
  REFRESH_TOKEN_TTL_MS,
  TokenService,
} from "./security.js";
import * as v from "./validation.js";

export type Log = { warn: (msg: string) => void };

export function requireRole(principal: Principal, ...roles: Role[]): void {
  if (!roles.includes(principal.role)) throw forbidden(`requires role ${roles.join(" or ")}`);
}

export class AuthService {
  constructor(
    readonly users: UserRepository,
    private readonly hasher: PasswordHasher,
    private readonly tokens: TokenService,
    private readonly clock: Clock,
    private readonly log: Log = { warn: (msg) => console.warn(msg) },
  ) {}

  // ---- accounts ----
  async register(payload: unknown): Promise<User> {
    const data = v.requireObject(payload);
    const email = v.email(data.email);
    const password = v.password(data.password);
    const name = v.displayName(data.displayName);
    // Never read "role" from the request: everyone starts as READER (mass-assignment defence).
    try {
      return await this.users.insertUser(
        email,
        await this.hasher.hash(password),
        name,
        "READER",
        this.clock(),
      );
    } catch (err) {
      if (err instanceof DuplicateError) throw emailTaken();
      throw err;
    }
  }

  async login(payload: unknown): Promise<TokenPair> {
    const data = v.requireObject(payload);
    const { email, password } = data;
    if (typeof email !== "string" || typeof password !== "string") {
      throw validationError("email and password are required");
    }
    const user = await this.users.findUserByEmail(email.trim().toLowerCase());
    // verify() also runs for unknown e-mails (against a dummy hash): same answer, same timing.
    const ok =
      Buffer.byteLength(password, "utf8") <= 72 &&
      (await this.hasher.verify(password, user?.passwordHash ?? null));
    if (!ok || !user) throw invalidCredentials();
    return this.issue(user);
  }

  async refresh(payload: unknown): Promise<TokenPair> {
    const token = refreshTokenFrom(payload);
    const stored = await this.users.findRefreshToken(hashRefreshToken(token));
    const now = this.clock();
    if (!stored) throw invalidRefreshToken();
    if (stored.revokedAt || !(await this.users.revokeRefreshToken(stored.id, now))) {
      // A used token came back: someone has a copy. Log out every session of this user.
      this.log.warn(`refresh token reuse detected for user ${stored.userId}: revoking all sessions`);
      await this.users.revokeAllRefreshTokens(stored.userId, now);
      throw invalidRefreshToken();
    }
    if (stored.expiresAt.getTime() <= now.getTime()) throw invalidRefreshToken();
    const user = await this.users.findUser(stored.userId);
    if (!user) throw invalidRefreshToken();
    return this.issue(user); // the role is re-read from the database here
  }

  async logout(payload: unknown): Promise<void> {
    const stored = await this.users.findRefreshToken(hashRefreshToken(refreshTokenFrom(payload)));
    if (stored) await this.users.revokeRefreshToken(stored.id, this.clock());
  }

  async me(principal: Principal): Promise<User> {
    const user = await this.users.findUser(principal.userId);
    if (!user) throw unauthorized("account no longer exists");
    return user;
  }

  async changeRole(principal: Principal, userId: number, payload: unknown): Promise<User> {
    requireRole(principal, "ADMIN");
    const newRole = v.role(v.requireObject(payload).role);
    if (userId === principal.userId) throw validationError("you cannot change your own role");
    const user = await this.users.updateRole(userId, newRole);
    if (!user) throw notFound("user");
    return user;
  }

  /** Start-up bootstrap from ADMIN_EMAIL / ADMIN_PASSWORD (there is no other way to get an ADMIN). */
  async ensureAdmin(rawEmail: string, password: string): Promise<User> {
    const email = v.email(rawEmail);
    const existing = await this.users.findUserByEmail(email);
    if (existing)
      return existing.role === "ADMIN" ? existing : (await this.users.updateRole(existing.id, "ADMIN"))!;
    return this.users.insertUser(
      email,
      await this.hasher.hash(v.password(password)),
      "Admin",
      "ADMIN",
      this.clock(),
    );
  }

  // ---- tokens ----
  async authenticate(authorization: string | undefined): Promise<Principal> {
    const principal = await this.optional(authorization);
    if (!principal) throw unauthorized();
    return principal;
  }

  /** No header -> anonymous. A header that is present but wrong is always a 401, never anonymous. */
  async optional(authorization: string | undefined): Promise<Principal | null> {
    if (authorization === undefined) return null;
    const space = authorization.indexOf(" ");
    const scheme = space < 0 ? authorization : authorization.slice(0, space);
    const token = space < 0 ? "" : authorization.slice(space + 1).trim();
    if (scheme.toLowerCase() !== "bearer" || !token) throw unauthorized("use Authorization: Bearer <token>");
    return this.tokens.verifyAccessToken(token);
  }

  private async issue(user: User): Promise<TokenPair> {
    const { token, hash } = newRefreshToken();
    const now = this.clock();
    await this.users.insertRefreshToken(user.id, hash, now, new Date(now.getTime() + REFRESH_TOKEN_TTL_MS));
    return { accessToken: await this.tokens.issueAccessToken(user), refreshToken: token, user };
  }
}

function refreshTokenFrom(payload: unknown): string {
  const token = v.requireObject(payload).refreshToken;
  if (typeof token !== "string" || !token) throw validationError("refreshToken is required");
  return token;
}
