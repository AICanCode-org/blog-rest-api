// M2: registration, login, refresh rotation and roles, at the service level (FR-1, FR-2, FR-3).
import { beforeEach, expect, it } from "vitest";
import { AuthService } from "../../src/auth.js";
import { InMemoryStore } from "../../src/repository.js";
import { PasswordHasher, TokenService } from "../../src/security.js";
import { FakeClock, silentLog, statusOf, TEST_SECRET } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("auth");
const REG = { email: "Asha@DevBlog.test", password: "password-123", displayName: "Asha" };

describe("auth service", () => {
  let store: InMemoryStore;
  let clock: FakeClock;
  let auth: AuthService;
  beforeEach(() => {
    store = new InMemoryStore();
    clock = new FakeClock();
    auth = new AuthService(
      store,
      new PasswordHasher(4),
      new TokenService(TEST_SECRET, clock.fn),
      clock.fn,
      silentLog,
    );
  });

  it("stores a bcrypt hash and a lower-case e-mail", async () => {
    const user = await auth.register(REG);
    expect([user.email, user.role, user.displayName]).toEqual(["asha@devblog.test", "READER", "Asha"]);
    const stored = store.users.get(user.id)!;
    expect(stored.passwordHash.startsWith("$2")).toBe(true);
    expect(stored.passwordHash).not.toContain("password-123");
  });

  it("ignores a role in the registration body", async () => {
    // Mass assignment: copying request fields into the model would let anyone become ADMIN.
    expect((await auth.register({ ...REG, role: "ADMIN" })).role).toBe("READER");
  });

  it("answers 409 for a duplicate e-mail, case-insensitively", async () => {
    await auth.register(REG);
    expect(await statusOf(auth.register({ ...REG, email: "asha@devblog.TEST" }))).toEqual([
      409,
      "email-taken",
    ]);
  });

  it("validates registrations", async () => {
    expect((await statusOf(auth.register({ ...REG, password: "short" })))[0]).toBe(400);
    expect((await statusOf(auth.register(["not", "an", "object"])))[0]).toBe(400);
  });

  it("returns tokens on login", async () => {
    await auth.register(REG);
    const pair = await auth.login({ email: " ASHA@devblog.test", password: "password-123" });
    expect(pair.user.email).toBe("asha@devblog.test");
    expect((await auth.authenticate(`Bearer ${pair.accessToken}`)).userId).toBe(pair.user.id);
  });

  it("gives a wrong password and an unknown e-mail the same answer", async () => {
    await auth.register(REG);
    const wrong = await statusOf(auth.login({ email: REG.email, password: "password-124" }));
    const unknown = await statusOf(auth.login({ email: "nobody@devblog.test", password: "password-123" }));
    expect(wrong).toEqual([401, "invalid-credentials"]);
    expect(unknown).toEqual(wrong);
    expect((await statusOf(auth.login({ email: REG.email })))[0]).toBe(400);
  });

  it("rotates the refresh token", async () => {
    await auth.register(REG);
    const first = await auth.login(REG);
    const second = await auth.refresh({ refreshToken: first.refreshToken });
    expect(second.refreshToken).not.toBe(first.refreshToken);
    expect((await auth.authenticate(`Bearer ${second.accessToken}`)).userId).toBe(first.user.id);
  });

  it("revokes every session when a refresh token is reused", async () => {
    await auth.register(REG);
    const stolen = await auth.login(REG);
    const otherDevice = await auth.login(REG);
    await auth.refresh({ refreshToken: stolen.refreshToken }); // the legitimate rotation
    expect(await statusOf(auth.refresh({ refreshToken: stolen.refreshToken }))).toEqual([
      401,
      "invalid-refresh-token",
    ]);
    // ...and the other device's still-unused token is now revoked too
    expect((await statusOf(auth.refresh({ refreshToken: otherDevice.refreshToken })))[0]).toBe(401);
  });

  it("expires refresh tokens after 7 days", async () => {
    await auth.register(REG);
    const pair = await auth.login(REG);
    clock.advance({ days: 7 });
    expect(await statusOf(auth.refresh({ refreshToken: pair.refreshToken }))).toEqual([
      401,
      "invalid-refresh-token",
    ]);
  });

  it("rejects unknown and missing refresh tokens", async () => {
    expect(await statusOf(auth.refresh({ refreshToken: "made-up" }))).toEqual([401, "invalid-refresh-token"]);
    expect((await statusOf(auth.refresh({})))[0]).toBe(400);
  });

  it("picks up a new role on refresh", async () => {
    const user = await auth.register(REG);
    const pair = await auth.login(REG);
    store.users.get(user.id)!.role = "AUTHOR";
    expect((await auth.authenticate(`Bearer ${pair.accessToken}`)).role).toBe("READER"); // stateless: old claim
    const renewed = await auth.refresh({ refreshToken: pair.refreshToken });
    expect((await auth.authenticate(`Bearer ${renewed.accessToken}`)).role).toBe("AUTHOR");
  });

  it("revokes on logout and is idempotent", async () => {
    await auth.register(REG);
    const pair = await auth.login(REG);
    await auth.logout({ refreshToken: pair.refreshToken });
    await auth.logout({ refreshToken: pair.refreshToken });
    await auth.logout({ refreshToken: "never-existed" });
    expect((await statusOf(auth.refresh({ refreshToken: pair.refreshToken })))[0]).toBe(401);
  });

  it("applies the Authorization header rules", async () => {
    expect(await auth.optional(undefined)).toBeNull();
    for (const bad of ["", "Bearer", "Bearer ", "Basic abc", "Token xyz", "Bearer not-a-jwt"]) {
      expect(await statusOf(auth.optional(bad))).toEqual([401, "unauthorized"]);
    }
    expect(await statusOf(auth.authenticate(undefined))).toEqual([401, "unauthorized"]);
  });

  it("lets only admins change roles", async () => {
    const target = await auth.register(REG);
    const author = { userId: 99, role: "AUTHOR" } as const;
    const admin = { userId: 100, role: "ADMIN" } as const;
    expect(await statusOf(auth.changeRole(author, target.id, { role: "ADMIN" }))).toEqual([403, "forbidden"]);
    expect((await auth.changeRole(admin, target.id, { role: "AUTHOR" })).role).toBe("AUTHOR");
    expect(await statusOf(auth.changeRole(admin, 12345, { role: "AUTHOR" }))).toEqual([404, "not-found"]);
    expect((await statusOf(auth.changeRole(admin, target.id, { role: "GOD" })))[0]).toBe(400);
    expect((await statusOf(auth.changeRole(admin, admin.userId, { role: "READER" })))[0]).toBe(400);
  });

  it("returns the current user from me()", async () => {
    const user = await auth.register(REG);
    expect((await auth.me({ userId: user.id, role: "READER" })).email).toBe("asha@devblog.test");
    expect((await statusOf(auth.me({ userId: 999, role: "READER" })))[0]).toBe(401);
  });

  it("creates or promotes the bootstrap admin", async () => {
    const created = await auth.ensureAdmin("Root@DevBlog.test", "admin-password");
    expect([created.role, created.email]).toEqual(["ADMIN", "root@devblog.test"]);
    expect((await auth.ensureAdmin("root@devblog.test", "ignored-password")).id).toBe(created.id);
    const reader = await auth.register(REG);
    expect((await auth.ensureAdmin(REG.email, "whatever-123")).id).toBe(reader.id);
    expect((await auth.me({ userId: reader.id, role: "READER" })).role).toBe("ADMIN");
  });
});
