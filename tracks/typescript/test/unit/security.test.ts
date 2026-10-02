// M2: passwords and tokens (FR-1, FR-2, NFR-3).
import { decodeJwt, SignJWT } from "jose";
import { expect, it } from "vitest";
import { ApiError } from "../../src/errors.js";
import type { User } from "../../src/models.js";
import {
  hashRefreshToken,
  newRefreshToken,
  PasswordHasher,
  resolveSecret,
  TokenService,
} from "../../src/security.js";
import { FakeClock, TEST_SECRET } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("security");
const USER: User = {
  id: 7,
  email: "asha@devblog.test",
  passwordHash: "x",
  displayName: "Asha",
  role: "AUTHOR",
  createdAt: new Date(0),
};
const enc = (o: object) => Buffer.from(JSON.stringify(o)).toString("base64url");
const forge = (header: object, claims: object, signature = "") =>
  `${enc(header)}.${enc(claims)}.${signature}`;
const sign = (claims: Record<string, unknown>, secret: string) =>
  new SignJWT(claims).setProtectedHeader({ alg: "HS256" }).sign(new TextEncoder().encode(secret));

async function expect401(promise: Promise<unknown>) {
  await expect(promise).rejects.toBeInstanceOf(ApiError);
  await promise.catch((err: ApiError) => {
    expect(err.status).toBe(401);
    expect(err.slug).toBe("unauthorized");
  });
}

describe("security", () => {
  it("hashes passwords with bcrypt and verifies them", async () => {
    const hasher = new PasswordHasher(4);
    const hashed = await hasher.hash("correct horse");
    expect(hashed.startsWith("$2")).toBe(true);
    expect(hashed).not.toContain("correct horse");
    expect(await hasher.verify("correct horse", hashed)).toBe(true);
    expect(await hasher.verify("wrong horse", hashed)).toBe(false);
    expect(await hasher.hash("correct horse")).not.toBe(hashed); // a new salt every time
  });

  it("still runs bcrypt for an unknown user, and fails", async () => {
    expect(await new PasswordHasher(4).verify("anything", null)).toBe(false);
  });

  it("bounds the bcrypt cost", () => {
    expect(() => new PasswordHasher(3)).toThrow();
  });

  it("puts sub, role, iss, iat and a 15 minute exp in the access token", async () => {
    const clock = new FakeClock();
    const claims = decodeJwt(await new TokenService(TEST_SECRET, clock.fn).issueAccessToken(USER));
    expect([claims.sub, claims.role, claims.iss]).toEqual(["7", "AUTHOR", "devblog"]);
    expect(claims.exp! - claims.iat!).toBe(900);
    expect(claims.iat).toBe(Math.floor(clock.now.getTime() / 1000));
  });

  it("returns the principal for a valid token", async () => {
    const tokens = new TokenService(TEST_SECRET, new FakeClock().fn);
    expect(await tokens.verifyAccessToken(await tokens.issueAccessToken(USER))).toEqual({
      userId: 7,
      role: "AUTHOR",
    });
  });

  it("rejects an expired token", async () => {
    const clock = new FakeClock();
    const tokens = new TokenService(TEST_SECRET, clock.fn);
    const token = await tokens.issueAccessToken(USER);
    clock.advance({ minutes: 15 });
    await expect401(tokens.verifyAccessToken(token));
  });

  it.each([
    // "alg": "none" - the classic: an unsigned token that a careless verifier accepts
    [
      "alg none",
      async () => forge({ alg: "none", typ: "JWT" }, { sub: "1", role: "ADMIN", iss: "devblog", exp: 9e9 }),
    ],
    [
      "another secret",
      () => sign({ sub: "1", role: "ADMIN", iss: "devblog", exp: 9_999_999_999 }, "x".repeat(40)),
    ],
    ["wrong issuer", () => sign({ sub: "1", role: "ADMIN", iss: "evil", exp: 9_999_999_999 }, TEST_SECRET)],
    ["unknown role", () => sign({ sub: "1", role: "ROOT", iss: "devblog", exp: 9_999_999_999 }, TEST_SECRET)],
    ["no expiry", () => sign({ sub: "1", role: "ADMIN", iss: "devblog" }, TEST_SECRET)],
    ["not a jwt", async () => "not.a.jwt"],
  ])("rejects a forged or broken token: %s", async (_name, make) => {
    await expect401(new TokenService(TEST_SECRET, new FakeClock().fn).verifyAccessToken(await make()));
  });

  it("rejects a tampered payload", async () => {
    const tokens = new TokenService(TEST_SECRET, new FakeClock().fn);
    const [header, , signature] = (await tokens.issueAccessToken(USER)).split(".");
    const evil = enc({ sub: "7", role: "ADMIN", iss: "devblog", exp: 9e9 });
    await expect401(tokens.verifyAccessToken(`${header}.${evil}.${signature}`));
  });

  it("makes random refresh tokens and keeps only the hash", () => {
    const { token, hash } = newRefreshToken();
    expect(token).not.toBe(newRefreshToken().token);
    expect(token.length).toBeGreaterThanOrEqual(43);
    expect(hash).toBe(hashRefreshToken(token));
    expect(hash).toHaveLength(64);
  });

  it("applies the JWT_SECRET rules", () => {
    expect(resolveSecret(null, () => {}).length).toBeGreaterThanOrEqual(32); // random, dev only
    expect(resolveSecret("s".repeat(32))).toBe("s".repeat(32));
    expect(() => resolveSecret("too-short")).toThrow();
  });
});
