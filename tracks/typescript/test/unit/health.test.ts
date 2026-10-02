// Works from day one: the starter already serves /health (no business logic needed).
import { expect, it } from "vitest";
import { Api } from "../helpers.js";

it("reports the database", async () => {
  const api = await Api.create();
  expect((await api.request("GET", "/health")).json()).toEqual({ status: "UP", checks: { database: "UP" } });
  api.store.healthy = false;
  const r = await api.request("GET", "/health");
  expect(r.statusCode).toBe(503);
  expect(r.json()).toEqual({ status: "DOWN", checks: { database: "DOWN" } });
});
