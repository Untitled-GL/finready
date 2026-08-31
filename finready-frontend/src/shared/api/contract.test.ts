import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { SpringFinReadyApi } from "@/shared/api/spring/spring-api";

/**
 * Guards on the contract itself and on how the adapter reaches it.
 *
 * These exist because both failures are silent: a base URL that doubles the
 * `/api` segment 404s against a service that is actually up, and a field the
 * flow depends on can disappear from the spec without any type error if the
 * client never reads it.
 */

const spec = readFileSync(
  new URL("../../../../docs/openapi.yml", import.meta.url),
  "utf8",
);

describe("openapi contract", () => {
  it("is the version this client was written against", () => {
    expect(spec).toContain("version: 1.4.6");
  });

  it("declares the fields the server-driven flow depends on", () => {
    // Each of these replaced a place where the client would otherwise have to
    // invent an answer: the attempt-2 question, the post-resolution
    // destination, and the state a reload comes back to.
    for (const field of [
      "recheckQuestion:",
      "recheckQuestionSource:",
      "pendingQuestion:",
      "StaffResolutionResponse:",
      "riskState:",
      "canProceedToUnderstanding:",
      "closeEligibility:",
      "provenanceFailureReason:",
      "classifierStatus:",
      "coverageStatus:",
      "demoPresets:",
      "demoAnswers:",
      "DemoPreset:",
      "DemoAnswer:",
    ]) {
      expect(spec).toContain(field);
    }
  });

  it("keeps /api on the server URL, not on the paths", () => {
    // The paths in the spec start at /sessions; the servers entry carries the
    // /api prefix. Both halves have to stay that way or the adapter's URLs
    // come out as /api/api/...
    expect(spec).toMatch(/url:\s*\S+\/api\b/);
    expect(spec).toContain("  /sessions:");
    expect(spec).not.toContain("  /api/sessions:");
  });
});

describe("spring adapter urls", () => {
  /** Captures the URL the adapter would request, without a network call. */
  function urlFor(run: (api: SpringFinReadyApi) => Promise<unknown>, baseUrl: string) {
    let captured = "";
    const originalFetch = globalThis.fetch;
    globalThis.fetch = (async (input: RequestInfo | URL) => {
      captured = String(input);
      return new Response("{}", {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    }) as typeof fetch;

    return run(new SpringFinReadyApi(baseUrl))
      .then(() => captured)
      .finally(() => {
        globalThis.fetch = originalFetch;
      });
  }

  const baseUrl = "https://finready-backend.example/api";

  it("does not double the /api prefix", async () => {
    const calls = await Promise.all([
      urlFor((api) => api.getDemoProduct(), baseUrl),
      urlFor((api) => api.getSession("s1"), baseUrl),
      urlFor((api) => api.getReport("s1"), baseUrl),
      urlFor(
        (api) =>
          api.resolveByStaff("s1", "R01", {
            disposition: "RESOLVED_BY_STAFF",
            reason: "확인 후 처리했습니다",
            actor: "staff",
          }),
        baseUrl,
      ),
    ]);

    for (const url of calls) {
      expect(url.startsWith(baseUrl)).toBe(true);
      expect(url).not.toContain("/api/api");
    }
  });

  it("addresses each operation at its contract path", async () => {
    expect(await urlFor((api) => api.getDemoProduct(), baseUrl)).toBe(
      `${baseUrl}/products/demo`,
    );
    expect(await urlFor((api) => api.getSession("s1"), baseUrl)).toBe(
      `${baseUrl}/sessions/s1`,
    );
    expect(
      await urlFor(
        (api) =>
          api.resolveByStaff("s1", "R01", {
            disposition: "UNRESOLVED",
            reason: "다음 방문 시 재확인",
            actor: "staff",
          }),
        baseUrl,
      ),
    ).toBe(`${baseUrl}/sessions/s1/risks/R01/staff-resolution`);
  });
});
