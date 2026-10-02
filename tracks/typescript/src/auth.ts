// Accounts, login, token rotation and roles. Framework-free: the HTTP layer only parses and formats.
import type { Clock } from "./clock.js";
import { validationError } from "./errors.js";
import type { Principal, Role, TokenPair, User } from "./models.js";
import type { UserRepository } from "./repository.js";
import { PasswordHasher, TokenService } from "./security.js";
import * as v from "./validation.js";

export type Log = { warn: (msg: string) => void };

export function requireRole(principal: Principal, ...roles: Role[]): void {
  // TODO(phase-3 M2): 403 forbidden unless principal.role is one of the allowed roles.
  throw new Error("TODO(phase-3 M2): requireRole");
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
    // TODO(phase-3 M2): validate email, password, displayName; ALWAYS create a READER (ignore any 'role'
    // in the payload: mass assignment!); hash the password; DuplicateError -> 409 email-taken.
    throw new Error("TODO(phase-3 M2): AuthService.register");
  }

  async login(payload: unknown): Promise<TokenPair> {
    // TODO(phase-3 M2): same 401 invalid-credentials for unknown e-mail and wrong password; for an unknown
    // e-mail still run one bcrypt verify (timing). Return access + refresh token pair.
    throw new Error("TODO(phase-3 M2): AuthService.login");
  }

  async refresh(payload: unknown): Promise<TokenPair> {
    // TODO(phase-3 M2): hash the token, find it; unknown/expired -> 401 invalid-refresh-token. Revoke with
    // compare-and-set: if it was already revoked it is REUSE -> revoke all the user's tokens, 401. Re-read
    // the user's role, issue a new pair.
    throw new Error("TODO(phase-3 M2): AuthService.refresh");
  }

  async logout(payload: unknown): Promise<void> {
    // TODO(phase-3 M2): require a refreshToken string (400 otherwise); revoke it if it exists. Idempotent:
    // always succeeds.
    throw new Error("TODO(phase-3 M2): AuthService.logout");
  }

  async me(principal: Principal): Promise<User> {
    // TODO(phase-3 M2): the current user, 404 if it no longer exists.
    throw new Error("TODO(phase-3 M2): AuthService.me");
  }

  async changeRole(principal: Principal, userId: number, payload: unknown): Promise<User> {
    // TODO(phase-3 M2): ADMIN only (403); validate the role; changing your own role -> 400; unknown user
    // -> 404.
    throw new Error("TODO(phase-3 M2): AuthService.changeRole");
  }

  /** Start-up bootstrap from ADMIN_EMAIL / ADMIN_PASSWORD (there is no other way to get an ADMIN). */
  async ensureAdmin(rawEmail: string, password: string): Promise<User> {
    // TODO(phase-3 M2): create ADMIN_EMAIL as ADMIN, or promote it if it exists. Until you build it the
    // app starts without an admin.
    console.warn("TODO(phase-3 M2): ensureAdmin - starting without an admin");
    return null as never;
  }

  // ---- tokens ----
  async authenticate(authorization: string | undefined): Promise<Principal> {
    // TODO(phase-3 M2): header must be 'Bearer <jwt>'; missing or bad -> 401 unauthorized (the HTTP layer
    // adds WWW-Authenticate).
    throw new Error("TODO(phase-3 M2): AuthService.authenticate");
  }

  /** No header -> anonymous. A header that is present but wrong is always a 401, never anonymous. */
  async optional(authorization: string | undefined): Promise<Principal | null> {
    // TODO(phase-3 M2): no header -> null (anonymous). A header that is present but bad is still a 401:
    // never silently anonymous.
    throw new Error("TODO(phase-3 M2): AuthService.optional");
  }

  private async issue(user: User): Promise<TokenPair> {
    // TODO(phase-3 M2): HS256 JWT with sub (user id as string), role, iss 'devblog', iat, exp = now + 900
    // s, using the injected clock.
    throw new Error("TODO(phase-3 M2): AuthService.issue");
  }
}

export function refreshTokenFrom(payload: unknown): string {
  const token = v.requireObject(payload).refreshToken;
  if (typeof token !== "string" || !token) throw validationError("refreshToken is required");
  return token;
}
