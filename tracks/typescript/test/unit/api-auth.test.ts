// M2: the auth endpoints over HTTP - status codes, headers and JSON shapes from spec/openapi.yaml.
import { beforeEach, expect, it } from "vitest";
import { Api } from "../helpers.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("api-auth");
const PROBLEM = "application/problem+json";

describe("auth API", () => {
  let api: Api;
  beforeEach(async () => {
    api = await Api.create();
  });

  it("registers, logs in and answers /me", async () => {
    const r = await api.request("POST", "/api/v1/auth/register", {
      email: "Asha@DevBlog.test",
      password: "password-123",
      displayName: "Asha",
      role: "ADMIN",
    });
    expect(r.statusCode).toBe(201);
    expect(r.json()).toEqual({
      id: r.json().id,
      email: "asha@devblog.test",
      displayName: "Asha",
      role: "READER",
      createdAt: "2026-10-02T09:30:00.000Z",
    });
    const login = (
      await api.request("POST", "/api/v1/auth/login", {
        email: "asha@devblog.test",
        password: "password-123",
      })
    ).json();
    expect([login.tokenType, login.expiresIn, login.user.role]).toEqual(["Bearer", 900, "READER"]);
    expect(login.accessToken.split(".")).toHaveLength(3);
    expect(login.refreshToken).toBeTruthy();
    const me = await api.request("GET", "/api/v1/me", undefined, login);
    expect(me.statusCode).toBe(200);
    expect(me.json().email).toBe("asha@devblog.test");
  });

  it("answers errors with problem+json", async () => {
    const r = await api.request("POST", "/api/v1/auth/register", {
      email: "nope",
      password: "password-123",
      displayName: "A",
    });
    expect(r.statusCode).toBe(400);
    expect(r.headers["content-type"]).toContain(PROBLEM);
    expect(r.json().type).toBe("https://devblog.example/problems/validation-error");
    expect(r.json().detail).toBe("email must be a valid e-mail address");
  });

  it("rejects bodies that are not JSON objects", async () => {
    const raw = await api.app.inject({
      method: "POST",
      url: "/api/v1/auth/login",
      payload: "not json",
      headers: { "content-type": "application/json" },
    });
    expect(raw.statusCode).toBe(400);
    expect((await api.request("POST", "/api/v1/auth/login", ["a", "b"])).statusCode).toBe(400);
  });

  it("answers a duplicate e-mail with 409", async () => {
    await api.user();
    const r = await api.request("POST", "/api/v1/auth/register", {
      email: "USER1@devblog.test",
      password: "password-123",
      displayName: "X",
    });
    expect(r.statusCode).toBe(409);
    expect(r.json().type).toMatch(/\/email-taken$/);
  });

  it("answers a bad login with 401 invalid-credentials", async () => {
    await api.user();
    const r = await api.request("POST", "/api/v1/auth/login", {
      email: "user1@devblog.test",
      password: "wrong-password",
    });
    expect(r.statusCode).toBe(401);
    expect(r.json().type).toMatch(/\/invalid-credentials$/);
  });

  it("answers /me without or with a bad token with 401 and WWW-Authenticate", async () => {
    for (const header of [undefined, "Bearer nope", "Basic dXNlcjpwYXNz"]) {
      const r = await api.request("GET", "/api/v1/me", undefined, header);
      expect(r.statusCode).toBe(401);
      expect(r.headers["www-authenticate"]).toBe("Bearer");
      expect(r.json().type).toMatch(/\/unauthorized$/);
    }
  });

  it("rejects an expired access token", async () => {
    const session = await api.user();
    api.clock.advance({ minutes: 16 });
    expect((await api.request("GET", "/api/v1/me", undefined, session)).statusCode).toBe(401);
  });

  it("refreshes and logs out", async () => {
    const session = await api.user();
    const renewed = await api.request("POST", "/api/v1/auth/refresh", { refreshToken: session.refreshToken });
    expect(renewed.statusCode).toBe(200);
    expect(renewed.json().refreshToken).not.toBe(session.refreshToken);
    const reused = await api.request("POST", "/api/v1/auth/refresh", { refreshToken: session.refreshToken });
    expect(reused.statusCode).toBe(401);
    expect(reused.json().type).toMatch(/\/invalid-refresh-token$/);
    expect((await api.request("POST", "/api/v1/auth/logout", { refreshToken: "whatever" })).statusCode).toBe(
      204,
    );
    expect((await api.request("POST", "/api/v1/auth/logout", {})).statusCode).toBe(400);
  });

  it("lets an admin promote a reader, who then refreshes into the new role", async () => {
    const reader = await api.user();
    const admin = await api.admin();
    const url = `/api/v1/admin/users/${reader.user.id}/role`;
    expect((await api.request("PATCH", url, { role: "AUTHOR" }, reader)).statusCode).toBe(403);
    const r = await api.request("PATCH", url, { role: "AUTHOR" }, admin);
    expect(r.statusCode).toBe(200);
    expect(r.json().role).toBe("AUTHOR");
    const renewed = (
      await api.request("POST", "/api/v1/auth/refresh", { refreshToken: reader.refreshToken })
    ).json();
    expect(renewed.user.role).toBe("AUTHOR");
    expect(
      (await api.request("PATCH", "/api/v1/admin/users/abc/role", { role: "AUTHOR" }, admin)).statusCode,
    ).toBe(404);
    expect(
      (await api.request("PATCH", "/api/v1/admin/users/999/role", { role: "AUTHOR" }, admin)).statusCode,
    ).toBe(404);
  });

  it("answers unknown routes with a problem+json 404", async () => {
    const r = await api.request("GET", "/api/v1/nope");
    expect(r.statusCode).toBe(404);
    expect(r.headers["content-type"]).toContain(PROBLEM);
    expect(r.json().type).toMatch(/\/not-found$/);
  });
});
