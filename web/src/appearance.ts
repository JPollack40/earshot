import { useEffect, useState, useSyncExternalStore } from "react";

// How the web player looks. Stored per browser (localStorage), not synced: each device keeps its own choice.
// Glow is the default; Classic is the original phase 5b look, kept exactly as it was.

export type Theme = "glow" | "classic";
export type Density = "comfortable" | "compact";
export type PodcastView = "grid" | "list";

export interface Appearance {
  theme: Theme;
  density: Density;
  podcastView: PodcastView;
  nowPanel: boolean; // the Now Playing panel (Glow only); opens on each visit on wide screens, closing lasts the visit
}

const KEY = "earshot.appearance";
const DEFAULTS: Appearance = { theme: "glow", density: "comfortable", podcastView: "grid", nowPanel: false };

function read(): Appearance {
  try {
    const raw = localStorage.getItem(KEY);
    if (raw) return { ...DEFAULTS, ...(JSON.parse(raw) as Partial<Appearance>) };
  } catch {
    // Private window or blocked storage: use the defaults.
  }
  return DEFAULTS;
}

// The Now Playing panel starts open on every visit where it has its own column (wider than 1100 px).
// At narrower widths it floats over the page, so there it starts closed. Closing it lasts for the visit.
function wide(): boolean {
  try {
    return window.matchMedia("(min-width: 1101px)").matches;
  } catch {
    return true;
  }
}

let state: Appearance = { ...read(), nowPanel: wide() };
const listeners = new Set<() => void>();

function apply() {
  const root = document.documentElement;
  root.dataset.theme = state.theme;
  root.dataset.density = state.density;
}
apply();

export function setAppearance(patch: Partial<Appearance>) {
  state = { ...state, ...patch };
  try {
    localStorage.setItem(KEY, JSON.stringify(state));
  } catch {
    // Not saved, but still applied for this visit.
  }
  apply();
  for (const l of listeners) l();
}

export function useAppearance(): Appearance {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      return () => listeners.delete(l);
    },
    () => state,
  );
}

/** Matches a CSS media query, live (used for Glow's phone layout). */
export function useMedia(query: string): boolean {
  const [on, setOn] = useState(() => window.matchMedia(query).matches);
  useEffect(() => {
    const m = window.matchMedia(query);
    const f = () => setOn(m.matches);
    m.addEventListener("change", f);
    f();
    return () => m.removeEventListener("change", f);
  }, [query]);
  return on;
}

// ---- Artwork colour, for Glow's tinted headers ----

const colorCache = new Map<string, string>();

/** A stable colour made from any text, for artwork whose host doesn't let the browser read its pixels. */
function hashColor(text: string): string {
  let h = 0;
  for (let i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) >>> 0;
  return `hsl(${h % 360} 45% 38%)`;
}

/**
 * The artwork's dominant colour, darkened enough for white text on top (contrast at least 4.5:1).
 * Needs the image host to allow cross-origin reads (CORS); otherwise falls back to a colour from the URL.
 */
export function useArtColor(url: string | null | undefined, fallbackKey: string): string {
  const key = url || fallbackKey;
  const [color, setColor] = useState(() => colorCache.get(key) ?? hashColor(key));
  useEffect(() => {
    const cached = colorCache.get(key);
    if (cached) {
      setColor(cached);
      return;
    }
    setColor(hashColor(key));
    if (!url) return;
    let cancelled = false;
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.onload = () => {
      try {
        const c = document.createElement("canvas");
        c.width = c.height = 24;
        const g = c.getContext("2d", { willReadFrequently: true })!;
        g.drawImage(img, 0, 0, 24, 24);
        const d = g.getImageData(0, 0, 24, 24).data;
        // Average the more colourful pixels so grey borders and white text don't wash it out.
        let r = 0, gr = 0, b = 0, w = 0;
        for (let i = 0; i < d.length; i += 4) {
          const max = Math.max(d[i], d[i + 1], d[i + 2]);
          const min = Math.min(d[i], d[i + 1], d[i + 2]);
          const weight = 1 + (max - min) / 16;
          r += d[i] * weight;
          gr += d[i + 1] * weight;
          b += d[i + 2] * weight;
          w += weight;
        }
        const out = darken([r / w, gr / w, b / w]);
        colorCache.set(key, out);
        if (!cancelled) setColor(out);
      } catch {
        colorCache.set(key, hashColor(key)); // tainted canvas: the host doesn't allow reading the image
      }
    };
    img.onerror = () => colorCache.set(key, hashColor(key));
    img.src = url;
    return () => {
      cancelled = true;
    };
  }, [key]);
  return color;
}

function luminance([r, g, b]: number[]): number {
  const f = (v: number) => {
    const s = v / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
}

/** Scales the colour down until white text on it has at least 4.5:1 contrast (with margin). */
function darken(rgb: number[]): string {
  let c = rgb;
  for (let i = 0; i < 30 && 1.05 / (luminance(c) + 0.05) < 5; i++) c = c.map((v) => v * 0.88);
  return `rgb(${c.map((v) => Math.round(v)).join(" ")})`;
}
