import { useEffect, useState } from "react";
import { supabase } from "../supabase";

// Phase 16: Serena's note from app_config.banner (supabase/migrations/010_safe_updates.sql), shown at the
// top of the page until dismissed; a new note shows again. Read at load and hourly. A failed read shows nothing.

const DISMISSED_KEY = "earshot.bannerDismissed";
const HOUR_MS = 60 * 60 * 1000;

function readDismissed(): string | null {
  try {
    return localStorage.getItem(DISMISSED_KEY);
  } catch {
    return null;
  }
}

export function ConfigBanner() {
  const [text, setText] = useState<string | null>(null);
  const [dismissed, setDismissed] = useState<string | null>(readDismissed);

  useEffect(() => {
    let alive = true;
    const load = async () => {
      const { data, error } = await supabase.from("app_config").select("banner").eq("id", 1).maybeSingle();
      if (!alive || error) return;
      const b = typeof data?.banner === "string" ? data.banner.trim() : "";
      setText(b || null);
    };
    void load();
    const t = window.setInterval(() => void load(), HOUR_MS);
    return () => {
      alive = false;
      window.clearInterval(t);
    };
  }, []);

  if (!text || dismissed === text) return null;
  return (
    <div className="config-banner" role="status">
      <span>{text}</span>
      <button
        onClick={() => {
          try {
            localStorage.setItem(DISMISSED_KEY, text);
          } catch {
            // private window: hide for this visit only
          }
          setDismissed(text);
        }}
      >
        OK
      </button>
    </div>
  );
}
