// v1.1 (Phase 7): optional cover image URL on posts (V2 migration).
import { describe, expect, it } from "vitest";
import { ApiError } from "../../src/errors.js";
import { coverImageUrl } from "../../src/validation.js";
import { Api } from "../helpers.js";

describe("coverImageUrl validation", () => {
  it.each(["https://img.example/cover.png", "http://localhost:8080/a.jpg?w=800#top", "HTTPS://CDN.EXAMPLE"])(
    "accepts %s",
    (url) => expect(coverImageUrl(url)).toBe(url),
  );

  it.each([
    "",
    "javascript:alert(1)",
    "data:image/png;base64,AAAA",
    "ftp://x.example/a.png",
    "/relative.png",
    "https://exa mple.com",
    "https://",
    42,
    "https://x.example/" + "a".repeat(2040),
  ])("rejects %j", (url) => {
    try {
      coverImageUrl(url);
      expect.unreachable();
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError);
      expect((err as ApiError).status).toBe(400);
      expect((err as ApiError).detail).toContain("coverImageUrl");
    }
  });

  it("null means no cover", () => expect(coverImageUrl(null)).toBeNull());
});

describe("cover image over HTTP", () => {
  it("is set on create, changed, kept, cleared, and shown in the feed", async () => {
    const api = await Api.create();
    const author = await api.user("AUTHOR");
    const post = await api.post(author, { coverImageUrl: "https://img.example/a.png" });
    expect(post.coverImageUrl).toBe("https://img.example/a.png");
    expect((await api.request("GET", "/api/v1/posts")).json().items[0].coverImageUrl).toBe(
      "https://img.example/a.png",
    );
    const url = `/api/v1/posts/${post.slug}`;
    let r = await api.request(
      "PATCH",
      url,
      { version: 1, coverImageUrl: "https://img.example/b.png" },
      author,
    );
    expect(r.json().coverImageUrl).toBe("https://img.example/b.png");
    r = await api.request("PATCH", url, { version: 2, title: "Only the title" }, author);
    expect(r.json().coverImageUrl).toBe("https://img.example/b.png");
    r = await api.request("PATCH", url, { version: 3, coverImageUrl: null }, author);
    expect(r.statusCode).toBe(200);
    expect(r.json().coverImageUrl).toBeNull();
    r = await api.request("PATCH", url, { version: 4, coverImageUrl: "javascript:alert(1)" }, author);
    expect(r.statusCode).toBe(400);
  });

  it("the nothing-to-update message mentions the cover", async () => {
    const api = await Api.create();
    const author = await api.user("AUTHOR");
    const post = await api.post(author);
    const r = await api.request("PATCH", `/api/v1/posts/${post.slug}`, { version: 1 }, author);
    expect(r.json().detail).toBe("nothing to update: send title, body, tags, status or coverImageUrl");
  });
});
