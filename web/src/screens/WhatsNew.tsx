import { useEffect, useState } from "react";

// What's new: the notes of the latest GitHub release. Opens by itself once after a new release
// (the first visit only remembers the version), and from the Theme menu at any time.

const API = "https://api.github.com/repos/JPollack40/earshot/releases/latest";
const PAGE = "https://github.com/JPollack40/earshot/releases/latest";
const SEEN_KEY = "earshot.whatsNewSeen";
const OPEN_EVENT = "earshot:whats-new";

interface Notes {
  version: string;
  body: string;
  url: string;
}

let cached: Promise<Notes> | null = null;

function fetchNotes(): Promise<Notes> {
  cached ??= fetch(API, { headers: { Accept: "application/vnd.github+json" } })
    .then((r) => {
      if (!r.ok) throw new Error(`GitHub answered ${r.status}`);
      return r.json();
    })
    .then((o) => ({ version: String(o.tag_name ?? "").replace(/^v/, ""), body: String(o.body ?? "").trim(), url: String(o.html_url ?? PAGE) }))
    .catch((e) => {
      cached = null;
      throw e;
    });
  return cached;
}

/** GitHub's Markdown, made readable as plain text (same rules as the phone). */
export function plainNotes(markdown: string): string {
  return markdown
    .replace(/\r\n/g, "\n")
    .split("\n")
    .map((line) =>
      line
        .replace(/^#{1,6}\s*/, "")
        .replace(/^\s*[-*]\s+/, "• ")
        .replace(/\*\*/g, "")
        .replace(/\[([^\]]+)]\([^)]+\)/g, "$1")
        .replace(/`/g, ""),
    )
    .join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

function readSeen(): string | null {
  try {
    return localStorage.getItem(SEEN_KEY);
  } catch {
    return null;
  }
}

function writeSeen(v: string) {
  try {
    localStorage.setItem(SEEN_KEY, v);
  } catch {
    /* private window: it may show again next time */
  }
}

/** Opens the What's new window (used by the Theme menu). */
export function openWhatsNew() {
  window.dispatchEvent(new Event(OPEN_EVENT));
}

export function WhatsNew() {
  const [open, setOpen] = useState(false);
  const [notes, setNotes] = useState<Notes | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    fetchNotes()
      .then((n) => {
        if (!alive || !n.version) return;
        const seen = readSeen();
        if (seen == null) writeSeen(n.version);
        else if (seen !== n.version) {
          setNotes(n);
          setOpen(true);
        }
      })
      .catch(() => {});
    const show = () => {
      setOpen(true);
      setError(null);
      fetchNotes()
        .then((n) => alive && setNotes(n))
        .catch((e) => alive && setError(e instanceof Error ? e.message : String(e)));
    };
    window.addEventListener(OPEN_EVENT, show);
    return () => {
      alive = false;
      window.removeEventListener(OPEN_EVENT, show);
    };
  }, []);

  useEffect(() => {
    if (!open) return;
    const esc = (e: KeyboardEvent) => e.key === "Escape" && close();
    document.addEventListener("keydown", esc);
    return () => document.removeEventListener("keydown", esc);
  });

  function close() {
    if (notes?.version) writeSeen(notes.version);
    setOpen(false);
  }

  if (!open) return null;
  return (
    <div className="scrim" onMouseDown={(e) => e.target === e.currentTarget && close()}>
      <div className="sheet whats-new" role="dialog" aria-label="What's new">
        <div className="sheet-title">{notes?.version ? `What's new in ${notes.version}` : "What's new"}</div>
        {error ? <p className="wn-msg error">Couldn't load the release notes: {error}</p> : !notes ? <p className="wn-msg muted">Loading…</p> : <div className="wn-body">{plainNotes(notes.body) || "No notes for this release."}</div>}
        <div className="actions">
          <a className="text" href={notes?.url ?? PAGE} target="_blank" rel="noreferrer">
            Full release notes
          </a>
          <button onClick={close}>OK</button>
        </div>
      </div>
    </div>
  );
}
