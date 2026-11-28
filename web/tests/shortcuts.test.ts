import { describe, expect, it } from "vitest";
import { actionFor, stepSpeed } from "../src/shortcuts";

describe("keyboard shortcuts", () => {
  it("maps keys to actions", () => {
    expect(actionFor({ key: " ", code: "Space" })).toBe("toggle");
    expect(actionFor({ key: "k" })).toBe("toggle");
    expect(actionFor({ key: "ArrowLeft" })).toBe("back");
    expect(actionFor({ key: "L" })).toBe("forward");
    expect(actionFor({ key: "]" })).toBe("faster");
    expect(actionFor({ key: "?" })).toBe("help");
    expect(actionFor({ key: "x" })).toBeNull();
  });
  it("never while typing, with modifiers, or Space on a button", () => {
    expect(actionFor({ key: "k", target: { tagName: "INPUT" } })).toBeNull();
    expect(actionFor({ key: "k", target: { tagName: "DIV", isContentEditable: true } })).toBeNull();
    expect(actionFor({ key: "l", ctrlKey: true })).toBeNull();
    expect(actionFor({ key: " ", code: "Space", target: { tagName: "BUTTON" } })).toBeNull();
    expect(actionFor({ key: "k", target: { tagName: "BUTTON" } })).toBe("toggle");
  });
  it("steps speed by 0.1 within 0.5 to 3", () => {
    expect(stepSpeed(1, true)).toBe(1.1);
    expect(stepSpeed(1.2, false)).toBe(1.1);
    expect(stepSpeed(3, true)).toBe(3);
    expect(stepSpeed(0.5, false)).toBe(0.5);
  });
});
