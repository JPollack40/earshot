// Line icons for the Glow theme. Classic keeps its original text glyphs (see Glyph).
import { useAppearance } from "../appearance";

const paths: Record<string, React.ReactNode> = {
  queue: (
    <>
      <path d="M4 6h16M4 12h10M4 18h7" />
      <path d="M17 15v6l4-3z" />
    </>
  ),
  new: (
    <>
      <path d="M4 13h4l2 3h4l2-3h4" />
      <path d="M5 13 7 5h10l2 8v6H5z" />
    </>
  ),
  latest: (
    <>
      <circle cx="12" cy="12" r="8" />
      <path d="M12 8v4l3 2" />
    </>
  ),
  podcasts: (
    <>
      <rect x="4" y="4" width="7" height="7" rx="1.5" />
      <rect x="13" y="4" width="7" height="7" rx="1.5" />
      <rect x="4" y="13" width="7" height="7" rx="1.5" />
      <rect x="13" y="13" width="7" height="7" rx="1.5" />
    </>
  ),
  playlists: (
    <>
      <path d="M4 6h11M4 11h11M4 16h7" />
      <circle cx="17" cy="17" r="2.5" />
      <path d="M19.5 17V6h2.5" />
    </>
  ),
  rules: (
    <>
      <path d="M4 7h9M17 7h3M4 17h3M11 17h9" />
      <circle cx="15" cy="7" r="2" />
      <circle cx="9" cy="17" r="2" />
    </>
  ),
  plus: <path d="M12 5v14M5 12h14" />,
  play: <path d="M8 5v14l11-7z" fill="currentColor" stroke="none" />,
  pause: (
    <>
      <rect x="6" y="5" width="4" height="14" rx="1" fill="currentColor" stroke="none" />
      <rect x="14" y="5" width="4" height="14" rx="1" fill="currentColor" stroke="none" />
    </>
  ),
  back: (
    <>
      <path d="M4 12a8 8 0 1 0 2.5-5.8" />
      <path d="M4 4v5h5" />
    </>
  ),
  forward: (
    <>
      <path d="M20 12a8 8 0 1 1-2.5-5.8" />
      <path d="M20 4v5h-5" />
    </>
  ),
  close: <path d="M6 6l12 12M18 6 6 18" />,
  queueLast: <path d="M12 4v11M7 10l5 5 5-5M5 20h14" />,
  panel: (
    <>
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <path d="M15 4v16" />
    </>
  ),
  sync: (
    <>
      <path d="M20 11a8 8 0 0 0-14-4M4 13a8 8 0 0 0 14 4" />
      <path d="M6 3v4h4M18 21v-4h-4" />
    </>
  ),
  search: (
    <>
      <circle cx="11" cy="11" r="6" />
      <path d="m20 20-4.5-4.5" />
    </>
  ),
  sliders: <path d="M4 7h10M18 7h2M4 17h4M12 17h8M14 4v6M8 14v6" />,
  arrowLeft: <path d="M19 12H5M11 6l-6 6 6 6" />,
  pin: <path d="M9 4h6l-1 6 4 3v2H6v-2l4-3zM12 15v6" />,
  theme: (
    <>
      <circle cx="12" cy="12" r="8" />
      <path d="M12 4a8 8 0 0 0 0 16z" fill="currentColor" />
    </>
  ),
  signOut: <path d="M14 4h5v16h-5M10 8l-4 4 4 4M6 12h10" />,
  bookmark: <path d="M7 4h10v16l-5-4-5 4z" />,
  stats: <path d="M5 20V12M10 20V6M15 20v-9M20 20V4" />,
  bookmarkOn: <path d="M7 4h10v16l-5-4-5 4z" fill="currentColor" />,
  transcript: <path d="M5 6h14M5 10h14M5 14h9M5 18h6" />,
  sparkle: <path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8zM18.5 15.5l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8z" />,
  download: <path d="M12 4v11M7 10l5 5 5-5M5 20h14" />,
  findText: (
    <>
      <path d="M4 6h10M4 10h6M4 14h4" />
      <circle cx="15.5" cy="14.5" r="3.5" />
      <path d="m18 17 3 3" />
    </>
  ),
  more: (
    <>
      <circle cx="6" cy="12" r="1.2" fill="currentColor" />
      <circle cx="12" cy="12" r="1.2" fill="currentColor" />
      <circle cx="18" cy="12" r="1.2" fill="currentColor" />
    </>
  ),
};

export function Icon({ name, size = 18 }: { name: keyof typeof paths; size?: number }) {
  return (
    <svg className="svg-icon" width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {paths[name]}
    </svg>
  );
}

/** An icon in Glow, the original text glyph in Classic. */
export function Glyph({ name, text }: { name: keyof typeof paths; text: string }) {
  return useAppearance().theme === "glow" ? <Icon name={name} /> : <>{text}</>;
}
