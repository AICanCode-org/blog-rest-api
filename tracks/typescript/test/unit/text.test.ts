// M1: slugs, excerpts and keyset cursors (FR-5, FR-6).
import { expect, it } from "vitest";
import { ApiError } from "../../src/errors.js";
import { decodeCursor, encodeCursor, excerpt, plainText, slugify } from "../../src/text.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("text");
const b64 = (s: string) => Buffer.from(s).toString("base64url");

describe("text", () => {
  it.each([
    ["Hello DevBlog", "hello-devblog"],
    ["Café & Code: Part 2!", "cafe-code-part-2"],
    ["  --Spaces   and---dashes--  ", "spaces-and-dashes"],
    ["!!!", "post"],
    ["数据库", "post"],
  ])("slugify(%j) = %j", (title, slug) => expect(slugify(title)).toBe(slug));

  it("keeps slugs at most 80 characters, never ending with a dash", () => {
    expect(slugify("a".repeat(79) + " b")).toBe("a".repeat(79));
    expect(slugify("word ".repeat(40)).length).toBeLessThanOrEqual(80);
    expect(slugify("word ".repeat(40)).endsWith("-")).toBe(false);
  });

  it("drops markdown symbols and collapses whitespace", () => {
    expect(plainText("# Title\n\n**Bold**  and `code`\n> quote")).toBe("Title Bold and code quote");
  });

  it("keeps excerpts at most 160 characters", () => {
    expect(excerpt("Short body.")).toBe("Short body.");
    const long = excerpt("word ".repeat(100));
    expect(long.length).toBeLessThanOrEqual(160);
    expect(long.endsWith("...")).toBe(true);
    expect(excerpt("x".repeat(160))).toBe("x".repeat(160));
    expect(excerpt("x".repeat(161))).toBe("x".repeat(157) + "...");
  });

  it("round-trips cursors", () => {
    const at = new Date("2026-10-02T09:30:00.123Z");
    const cursor = encodeCursor(at, 42);
    expect(cursor).not.toContain("="); // URL-safe, no padding
    expect(decodeCursor(cursor)).toEqual({ publishedAt: at, id: 42 });
    expect(decodeCursor(undefined)).toBeNull();
    expect(decodeCursor("")).toBeNull();
  });

  it.each([
    "not-base64!!",
    "aGVsbG8",
    "MjAyNi0wMS0wMVQwMDowMDowMC4wMDBa", // a valid cursor with its last 3 characters cut off
    b64("2026-10-02T09:30:00Z|1"), // no milliseconds
    b64("2026-13-02T09:30:00.000Z|1"), // month 13
  ])("rejects the cursor %j with a 400", (bad) => {
    expect(() => decodeCursor(bad)).toThrow(ApiError);
  });
});
