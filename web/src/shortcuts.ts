// Phase 22: keyboard shortcuts for the web player, and the "?" sheet that lists them.

export type ShortcutAction = "toggle" | "back" | "forward" | "slower" | "faster" | "next" | "search" | "help" | "close";

export const SHORTCUTS: { keys: string[]; action: ShortcutAction; label: string }[] = [
  { keys: ["Space", "K"], action: "toggle", label: "Play or pause" },
  { keys: ["←", "J"], action: "back", label: "Skip back" },
  { keys: ["→", "L"], action: "forward", label: "Skip forward" },
  { keys: ["["], action: "slower", label: "Slower (0.1×)" },
  { keys: ["]"], action: "faster", label: "Faster (0.1×)" },
  { keys: ["N"], action: "next", label: "Play the next episode in the queue" },
  { keys: ["/"], action: "search", label: "Search transcripts" },
  { keys: ["?"], action: "help", label: "Show these shortcuts" },
  { keys: ["Esc"], action: "close", label: "Close this list" },
];

/** What a key press does, or null (typing in a field, or with Ctrl / Alt / Cmd held, never counts). */
export function actionFor(ev: { key: string; code?: string; ctrlKey?: boolean; metaKey?: boolean; altKey?: boolean; target?: unknown }): ShortcutAction | null {
  if (ev.ctrlKey || ev.metaKey || ev.altKey) return null;
  const t = ev.target as { tagName?: string; isContentEditable?: boolean } | null | undefined;
  if (t && (["INPUT", "TEXTAREA", "SELECT"].includes(t.tagName ?? "") || t.isContentEditable)) return null;
  // Space on a focused button presses the button, as the browser intends.
  if ((ev.key === " " || ev.code === "Space") && t?.tagName === "BUTTON") return null;
  switch (ev.key) {
    case " ":
    case "k":
    case "K":
      return "toggle";
    case "ArrowLeft":
    case "j":
    case "J":
      return "back";
    case "ArrowRight":
    case "l":
    case "L":
      return "forward";
    case "[":
      return "slower";
    case "]":
      return "faster";
    case "n":
    case "N":
      return "next";
    case "/":
      return "search";
    case "?":
      return "help";
    case "Escape":
      return "close";
    default:
      return null;
  }
}

/** The next speed for "[" and "]": 0.1× steps between 0.5× and 3×. */
export function stepSpeed(speed: number, up: boolean): number {
  const next = Math.round((speed + (up ? 0.1 : -0.1)) * 10) / 10;
  return Math.min(3, Math.max(0.5, next));
}
