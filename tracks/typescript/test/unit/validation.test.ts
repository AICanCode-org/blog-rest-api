// M1: every input rule from spec/requirements.md (FR-1, FR-4, FR-7).
import { expect, it } from "vitest";
import { ApiError } from "../../src/errors.js";
import * as v from "../../src/validation.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("validation");

function rejects(fn: () => unknown, status = 400) {
  try {
    fn();
  } catch (err) {
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(status);
    return;
  }
  throw new Error("expected a validation error");
}

describe("validation", () => {
  it("trims and lower-cases e-mails", () => {
    expect(v.email("  Asha@Example.COM ")).toBe("asha@example.com");
  });

  it.each([null, 42, "", "no-at-sign", "a@b", "a b@c.dev", "@x.dev", "a@" + "x".repeat(250) + ".dev"])(
    "rejects the e-mail %s",
    (bad) => rejects(() => v.email(bad)),
  );

  it("measures passwords in characters and bytes", () => {
    expect(v.password("12345678")).toBe("12345678");
    expect(v.password("x".repeat(72))).toBe("x".repeat(72));
    rejects(() => v.password("1234567"));
    rejects(() => v.password("x".repeat(73)));
    rejects(() => v.password("é".repeat(37))); // 37 characters but 74 bytes: bcrypt only reads 72 bytes
    rejects(() => v.password(null));
  });

  it("checks display names and roles", () => {
    expect(v.displayName("  Asha  ")).toBe("Asha");
    rejects(() => v.displayName("   "));
    rejects(() => v.displayName("x".repeat(51)));
    expect(v.role("AUTHOR")).toBe("AUTHOR");
    rejects(() => v.role("author"));
    rejects(() => v.role("ROOT"));
  });

  it("checks titles and bodies", () => {
    expect(v.title("  Hello  ")).toBe("Hello");
    rejects(() => v.title(""));
    rejects(() => v.title("x".repeat(201)));
    expect(v.body("  keep *markdown* as is ")).toBe("  keep *markdown* as is ");
    rejects(() => v.body("   "));
    rejects(() => v.body("x".repeat(50_001)));
    rejects(() => v.body(7));
  });

  it("normalises, de-duplicates and sorts tags", () => {
    expect(v.tags(undefined)).toEqual([]);
    expect(v.tags(null)).toEqual([]);
    expect(v.tags([" Java", "spring-boot", "java"])).toEqual(["java", "spring-boot"]);
  });

  it.each([
    ["java"],
    [[1]],
    [["a", "b", "c", "d", "e", "f"]],
    [["two words"]],
    [["-java"]],
    [["a--b"]],
    [["x".repeat(31)]],
  ])("rejects the tags %j", (bad) => rejects(() => v.tags(bad)));

  it("checks status and version", () => {
    expect(v.status(undefined)).toBe("draft");
    expect(v.status("published")).toBe("published");
    rejects(() => v.status("archived"));
    rejects(() => v.status(null, null)); // in a PATCH, an explicit null is not "keep the old value"
    expect(v.version(3)).toBe(3);
    rejects(() => v.version(undefined));
    rejects(() => v.version(0));
    rejects(() => v.version("1"));
    rejects(() => v.version(true));
    rejects(() => v.version(1.5));
  });

  it("checks comment bodies and parents", () => {
    expect(v.commentBody("  nice post  ")).toBe("nice post");
    rejects(() => v.commentBody(" "));
    rejects(() => v.commentBody("x".repeat(2001)));
    expect(v.parentId(undefined)).toBeNull();
    expect(v.parentId(5)).toBe(5);
    rejects(() => v.parentId(0));
    rejects(() => v.parentId("5"));
    rejects(() => v.parentId(false));
  });

  it("checks feed parameters", () => {
    expect(v.limit(undefined)).toBe(10);
    expect(v.limit("50")).toBe(50);
    for (const bad of ["0", "51", "-1", "ten", "1.5", "²", "\u0661\u0660", ""]) rejects(() => v.limit(bad));
    expect(v.tagFilter(undefined)).toBeNull();
    expect(v.tagFilter("")).toBeNull();
    expect(v.tagFilter("Java")).toBe("java");
    rejects(() => v.tagFilter("not a tag"));
    expect(v.searchQuery("  ")).toBeNull();
    expect(v.searchQuery(" docker ")).toBe("docker");
    rejects(() => v.searchQuery("x".repeat(101)));
  });

  it("checks admin parameters", () => {
    expect(v.commentStatus(undefined)).toBe("held");
    expect(v.commentStatus("rejected")).toBe("rejected");
    rejects(() => v.commentStatus("deleted"));
    expect(v.decision("approve")).toBe("approve");
    rejects(() => v.decision("maybe"));
  });

  it("requires a JSON object body", () => {
    expect(v.requireObject({ a: 1 })).toEqual({ a: 1 });
    for (const bad of [null, [], "text", 3, undefined]) rejects(() => v.requireObject(bad));
  });

  it("treats path ids that cannot exist as 404", () => {
    expect(v.pathId("42", "comment")).toBe(42);
    for (const bad of ["abc", "-1", "1.0", "9".repeat(19), "²"]) rejects(() => v.pathId(bad, "comment"), 404);
  });
});
