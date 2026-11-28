import { describe, expect, it, vi } from "vitest";

// handoff.ts pulls in the player (Audio elements) and Supabase; only its pure parts are tested here.
vi.mock("../src/player", () => ({ snapshot: () => ({ playing: false, episode: null }), hooks: {}, pause: () => {}, playAt: () => {} }));
vi.mock("../src/store", () => ({ episode: () => undefined }));
vi.mock("../src/supabase", () => ({ supabase: {} }));

import { holderLabel, shouldOffer, type Holder } from "../src/handoff";

const t0 = Date.parse("2026-11-28T08:30:00Z");
const h = (over: Partial<Holder> = {}): Holder => ({
  device: "android:abcd1234",
  device_name: "Samsung SM-F956U",
  episode_id: "ep1",
  position_ms: 61_000,
  playing: true,
  updated_at: "2026-11-28T08:30:00+00:00",
  ...over,
});

describe("handoff offer", () => {
  it("offers a phone that's playing now", () => {
    expect(shouldOffer(h(), t0 + 60_000, null)).toBe(true);
    expect(holderLabel(h())).toBe("Samsung SM-F956U");
  });
  it("not when it went quiet, or it's this browser, or the same episode plays here", () => {
    expect(shouldOffer(h(), t0 + 3 * 60_000, null)).toBe(false);
    expect(shouldOffer(h({ device: "web" }), t0, null)).toBe(false);
    expect(shouldOffer(h(), t0, "ep1")).toBe(false);
    expect(shouldOffer(null, t0, null)).toBe(false);
  });
  it("a pause is offered for 12 hours", () => {
    expect(shouldOffer(h({ playing: false }), t0 + 11 * 3_600_000, null)).toBe(true);
    expect(shouldOffer(h({ playing: false }), t0 + 13 * 3_600_000, null)).toBe(false);
  });
});
