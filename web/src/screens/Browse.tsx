import { useEffect, useState } from "react";
import * as B from "../browse";
import type { BrowseCard, BrowsePrefs } from "../browse";
import * as store from "../store";
import { useAppearance, useArtColor } from "../appearance";
import { feedKey } from "../util";
import { Art, type Nav } from "./common";
import { Glyph } from "./Icons";
import { Hero } from "./Sidebar";

// Phase 13: Browse Podcasts on the web (sidebar item). Tap a show to open its page as a preview;
// ⋯ > Not interested hides it for good. Same sections and settings as the phone.

/** Followed shows and shows hidden on this visit never show. */
function useExcluded(): (c: BrowseCard) => boolean {
  const s = store.useStore();
  const b = B.useBrowse();
  const followed = new Set(store.library(s).map((p) => feedKey(p.feed_url)));
  return (c) => b.hiddenNow.has(c.feedUrl) || followed.has(feedKey(c.feedUrl));
}

function Header({ nav, title, summary, back, children }: { nav: Nav; title: string; summary?: string; back?: boolean; children?: React.ReactNode }) {
  const glow = useAppearance().theme === "glow";
  const tint = useArtColor(null, "earshot-browse");
  const backButton = back && (
    <button className="icon" title="Back" onClick={nav.back}>
      <Glyph name="arrowLeft" text="←" />
    </button>
  );
  if (glow) {
    return (
      <Hero tint={tint} eyebrow="Find new shows" title={title} summary={summary}>
        <div className="st-hero-actions">
          {backButton}
          {children}
        </div>
      </Hero>
    );
  }
  return (
    <header className="bar">
      {backButton}
      <div className="grow">
        <h1 className="ellipsis">{title}</h1>
        {summary && <div className="meta ellipsis">{summary}</div>}
      </div>
      {children}
    </header>
  );
}

function Toast() {
  const b = B.useBrowse();
  useEffect(() => {
    if (!b.message) return;
    const t = window.setTimeout(B.clearMessage, 5000);
    return () => window.clearTimeout(t);
  }, [b.message]);
  return b.message ? <div className="notice browse-toast">{b.message}</div> : null;
}

/** An artwork card. Click opens the podcast (a preview); ⋯ offers Not interested. */
function Card({ c, nav }: { c: BrowseCard; nav: Nav }) {
  const [menu, setMenu] = useState(false);
  return (
    <div className="b-card">
      <button className="b-open" title={c.title} onClick={() => nav.open({ name: "podcast", feedUrl: c.feedUrl })}>
        <Art url={c.artworkUrl} size={140} />
        <div className="b-title">{c.title}</div>
        {c.reason && <div className="b-reason">{c.reason}</div>}
      </button>
      <button className="b-more" title="More" aria-expanded={menu} onClick={() => setMenu(!menu)}>
        ⋯
      </button>
      {menu && (
        <div className="b-menu" onMouseLeave={() => setMenu(false)}>
          <button
            onClick={() => {
              setMenu(false);
              void B.notInterested(c);
            }}
          >
            Not interested
          </button>
        </div>
      )}
    </div>
  );
}

export function BrowsePage({ nav }: { nav: Nav }) {
  const b = B.useBrowse();
  const excluded = useExcluded();
  useEffect(() => {
    void B.loadPage();
  }, []);
  const page = b.page;
  return (
    <>
      <Header nav={nav} title="Browse" summary="Suggestions from what you listen to, plus charts and categories">
        <button className="icon" title="All categories" onClick={() => nav.open({ name: "browseCategories" })}>
          <Glyph name="grid" text="▦" />
        </button>
        <button className="icon" title="Browse settings" disabled={!page} onClick={() => nav.open({ name: "browseSettings" })}>
          <Glyph name="sliders" text="⚙" />
        </button>
        <button className="icon" title="Reload suggestions" disabled={b.loading} onClick={() => void B.loadPage(true)}>
          {b.loading ? "…" : <Glyph name="sync" text="⟳" />}
        </button>
      </Header>
      <Toast />
      <div className="browse">
        {!page && b.error && (
          <div className="pad">
            <div className="error">Browse didn't load: {b.error}</div>
            <button onClick={() => void B.loadPage(true)}>Try again</button>
          </div>
        )}
        {!page && !b.error && <div className="center muted">Finding shows for you… (the first load can take up to half a minute)</div>}
        {page && b.error && <div className="error small pad">Couldn't refresh: {b.error}</div>}
        {page?.sections.map((s) => {
          const items = s.items.filter((c) => !excluded(c));
          if (!items.length && s.group !== "friends") return null;
          return (
            <section key={s.id} className="b-section">
              <div className="b-head">
                <div className="grow">
                  <h2>{s.title}</h2>
                  {s.subtitle && <div className="muted small">{s.subtitle}</div>}
                </div>
                {items.length > 4 && (
                  <button className="text" onClick={() => nav.open({ name: "browseList", title: s.title, category: s.category, items: s.category ? undefined : s.items })}>
                    See all
                  </button>
                )}
              </div>
              <div className="b-row">
                {items.map((c) => (
                  <Card key={c.feedUrl} c={c} nav={nav} />
                ))}
              </div>
            </section>
          );
        })}
        {page && page.notes.length > 0 && <div className="muted small pad">{page.notes.join(" · ")}</div>}
        {page && <div className="muted small b-sources">Charts: Apple Podcasts · Data: Podcast Index · Some suggestions by Gemini</div>}
      </div>
    </>
  );
}

/** "See all" for a row, or a category's shows, as a grid. */
export function BrowseListPage({ nav, title, category, items }: { nav: Nav; title: string; category?: string; items?: BrowseCard[] }) {
  const excluded = useExcluded();
  const [list, setList] = useState<BrowseCard[] | null>(items ?? null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (!category) return;
    B.categoryShows(category)
      .then(setList)
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)));
  }, [category]);
  const shown = list?.filter((c) => !excluded(c));
  return (
    <>
      <Header nav={nav} title={title} summary={category ? "Popular in this category" : undefined} back />
      <Toast />
      {error && <div className="error pad">{error}</div>}
      {!list && !error && <div className="center muted">Loading…</div>}
      {shown && shown.length === 0 && <div className="empty">Nothing to show: everything here is already in your library or hidden.</div>}
      {shown && shown.length > 0 && (
        <div className="b-grid">
          {shown.map((c) => (
            <Card key={c.feedUrl} c={c} nav={nav} />
          ))}
        </div>
      )}
    </>
  );
}

export function BrowseCategoriesPage({ nav }: { nav: Nav }) {
  const b = B.useBrowse();
  useEffect(() => {
    void B.loadCategories();
  }, []);
  return (
    <>
      <Header nav={nav} title="Categories" back />
      {!b.categories && <div className="center muted">Loading…</div>}
      <div className="b-cats">
        {b.categories?.map((c) => (
          <button key={c} onClick={() => nav.open({ name: "browseList", title: c, category: c })}>
            {c}
          </button>
        ))}
      </div>
    </>
  );
}

/** Which sections show and their order, category rows, chart country, friends, and hidden shows. */
export function BrowseSettingsPage({ nav }: { nav: Nav }) {
  const b = B.useBrowse();
  const start: BrowsePrefs = b.page?.prefs ?? { country: "us", categories: B.DEFAULT_CATEGORIES, hidden_sections: [], section_order: [], friends_opt_in: false };
  const startOrder = [...new Set([...(b.page?.groups ?? []), ...Object.keys(B.GROUP_NAMES)])].filter((g) => g in B.GROUP_NAMES);
  const [prefs, setPrefs] = useState<BrowsePrefs>(start);
  const [order, setOrder] = useState<string[]>(startOrder);
  const [adding, setAdding] = useState("");
  useEffect(() => {
    void B.loadHidden();
    void B.loadCategories();
  }, []);
  const changed = JSON.stringify({ ...prefs, section_order: order }) !== JSON.stringify({ ...start, section_order: startOrder });
  const move = (i: number, d: number) => {
    const next = [...order];
    const [g] = next.splice(i, 1);
    next.splice(i + d, 0, g);
    setOrder(next);
  };
  const save = () => {
    void B.savePrefs({ ...prefs, section_order: order });
    nav.back();
  };
  return (
    <>
      <Header nav={nav} title="Browse settings" back>
        <button className="primary" disabled={!changed || !b.page} onClick={save}>
          Save
        </button>
      </Header>
      <div className="browse-settings">
        <h3>Sections</h3>
        <div className="muted small">Tick to show; arrows to move.</div>
        {order.map((g, i) => (
          <div key={g} className="bs-row">
            <label className="grow">
              <input
                type="checkbox"
                checked={!prefs.hidden_sections.includes(g)}
                onChange={(ev) =>
                  setPrefs({ ...prefs, hidden_sections: ev.target.checked ? prefs.hidden_sections.filter((x) => x !== g) : [...prefs.hidden_sections, g] })
                }
              />{" "}
              {B.GROUP_NAMES[g] ?? g}
            </label>
            <button className="icon" title="Move up" disabled={i === 0} onClick={() => move(i, -1)}>
              ↑
            </button>
            <button className="icon" title="Move down" disabled={i === order.length - 1} onClick={() => move(i, 1)}>
              ↓
            </button>
          </div>
        ))}

        <h3>Category rows</h3>
        <div className="muted small">One row of popular shows for each (English only).</div>
        <div className="bs-chips">
          {prefs.categories.map((c) => (
            <button key={c} className="chip" title={`Remove ${c}`} onClick={() => setPrefs({ ...prefs, categories: prefs.categories.filter((x) => x !== c) })}>
              {c} ✕
            </button>
          ))}
        </div>
        <div className="actions">
          <select
            value={adding}
            onChange={(ev) => {
              const c = ev.target.value;
              if (c && !prefs.categories.includes(c)) setPrefs({ ...prefs, categories: [...prefs.categories, c] });
              setAdding("");
            }}
          >
            <option value="">Add a category…</option>
            {(b.categories ?? [])
              .filter((c) => !prefs.categories.includes(c))
              .map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
          </select>
          <button onClick={() => setPrefs({ ...prefs, categories: B.DEFAULT_CATEGORIES })}>Reset to the usual eight</button>
        </div>

        <h3>Top podcasts chart</h3>
        <label>
          Apple Podcasts chart for{" "}
          <select value={prefs.country} onChange={(ev) => setPrefs({ ...prefs, country: ev.target.value })}>
            {Object.entries(B.COUNTRIES).map(([code, name]) => (
              <option key={code} value={code}>
                {name}
              </option>
            ))}
          </select>
        </label>

        <h3>Popular with your friends</h3>
        <label>
          <input type="checkbox" checked={prefs.friends_opt_in} onChange={(ev) => setPrefs({ ...prefs, friends_opt_in: ev.target.checked })} /> Count my listening (no names) and show
          what other Earshot friends who turned this on listen to. A show appears only when at least 2 of them listen to it.
        </label>

        <h3>Not interested</h3>
        <div className="muted small">Shows you hid. Undo to see them in Browse again.</div>
        {!b.hidden && <div className="muted">Loading…</div>}
        {b.hidden?.length === 0 && <div className="muted small">None</div>}
        {b.hidden?.map((h) => (
          <div key={h.feed_url} className="bs-row">
            <Art url={h.image_url} size={32} />
            <span className="grow ellipsis">{h.title || h.feed_url}</span>
            <button onClick={() => void B.unhide(h.feed_url)}>Undo</button>
          </div>
        ))}
      </div>
    </>
  );
}

/** The "You might like" tab of a podcast page: its podroll first, then popular shows in its categories. */
export function SimilarList({ feedUrl, title, nav }: { feedUrl: string; title: string; nav: Nav }) {
  const b = B.useBrowse();
  const excluded = useExcluded();
  useEffect(() => {
    void B.loadSimilar(feedUrl, title);
  }, [feedUrl]);
  const st = b.similar.get(feedUrl);
  if (!st?.items) return <div className="center muted">Finding similar shows…</div>;
  if (st.error) return <div className="error pad">Suggestions didn't load: {st.error}</div>;
  const items = st.items.filter((c) => !excluded(c));
  if (!items.length) return <div className="empty">No suggestions yet: this show has no podroll, and nothing new turned up in its categories.</div>;
  return (
    <>
      <Toast />
      <div className="b-grid">
        {items.map((c) => (
          <Card key={c.feedUrl} c={c} nav={nav} />
        ))}
      </div>
    </>
  );
}
