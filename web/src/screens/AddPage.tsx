import { useState } from "react";
import { SUPABASE_KEY, SUPABASE_URL } from "../config";
import { fetchFeed } from "../feed";
import * as store from "../store";
import { normalizeUrl } from "../util";
import { Art, type Nav } from "./common";

interface Result {
  title: string;
  author: string;
  feedUrl: string;
  artworkUrl: string | null;
  episodeCount: number | null;
}

export function AddPage({ nav }: { nav: Nav }) {
  const s = store.useStore();
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<Result[]>([]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const looksLikeUrl = /:\/\//.test(query.trim()) || (/\./.test(query) && !/\s/.test(query.trim()));

  const search = async () => {
    const q = query.trim();
    if (!q) return;
    if (looksLikeUrl) {
      nav.open({ name: "podcast", feedUrl: normalizeUrl(q) });
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const res = await fetch(`${SUPABASE_URL}/functions/v1/podcast-search?q=${encodeURIComponent(q)}`, { headers: { apikey: SUPABASE_KEY } });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = (await res.json()) as { results: Result[] };
      setResults(data.results);
      if (!data.results.length) setMessage("No podcasts found");
    } catch (e) {
      setMessage(`Search failed: ${e instanceof Error ? e.message : e}`);
    } finally {
      setBusy(false);
    }
  };

  const importOpml = async (file: File) => {
    const doc = new DOMParser().parseFromString(await file.text(), "application/xml");
    const feeds = Array.from(doc.querySelectorAll("outline[xmlUrl], outline[xmlurl]")).map((o) => ({
      url: normalizeUrl(o.getAttribute("xmlUrl") ?? o.getAttribute("xmlurl") ?? ""),
      title: o.getAttribute("title") ?? o.getAttribute("text") ?? "",
    }));
    if (!feeds.length) {
      setMessage("That doesn't look like an OPML subscriptions file");
      return;
    }
    setBusy(true);
    const failed = await store.importFeeds(feeds, (u) => fetchFeed(u), (d, t) => setMessage(`Importing ${d} of ${t}…`));
    setBusy(false);
    setMessage(failed.length ? `Imported. Couldn't add: ${failed.join(", ")}` : "Imported all podcasts");
  };

  return (
    <>
      <header className="bar">
        <button className="icon" title="Back" onClick={nav.back}>
          ←
        </button>
        <h1>Add podcast</h1>
      </header>
      <form
        className="pad add-form"
        onSubmit={(e) => {
          e.preventDefault();
          void search();
        }}
      >
        <input className="search wide" autoFocus placeholder="Search podcasts or paste a feed URL" value={query} onChange={(e) => setQuery(e.target.value)} />
        <button className="primary" disabled={busy || !query.trim()}>
          {looksLikeUrl ? "Open feed" : "Search"}
        </button>
        <label className="button">
          Import OPML
          <input type="file" accept=".opml,.xml,text/xml,application/xml" hidden onChange={(e) => e.target.files?.[0] && void importOpml(e.target.files[0])} />
        </label>
      </form>
      {message && <div className="pad muted small">{message}</div>}
      {results.length > 0 && <div className="subbar muted small">From Podcast Index. Click a podcast to look before subscribing.</div>}
      <section>
        {results.map((r) => {
          const sub = store.findPodcast(r.feedUrl, s)?.subscribed;
          return (
            <div className="row" key={r.feedUrl}>
              <Art url={r.artworkUrl} size={44} />
              <button className="row-main" onClick={() => nav.open({ name: "podcast", feedUrl: r.feedUrl })}>
                <div className="title">{r.title}</div>
                <div className="meta">{[r.author, r.episodeCount != null ? `${r.episodeCount} episodes` : ""].filter(Boolean).join(" • ")}</div>
              </button>
              {sub ? <span className="check" title="Subscribed">✓</span> : null}
            </div>
          );
        })}
      </section>
    </>
  );
}
