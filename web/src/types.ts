// Rows of the Supabase sync tables (supabase/migrations/001_sync.sql). Times are ms since 1970.

export interface PodcastRow {
  feed_url: string;
  title: string;
  author: string;
  description: string;
  image_url: string | null;
  subscribed: boolean;
  pinned: boolean;
  speed: number | null;
  smart_speed: boolean | null;
  voice_boost: boolean | null;
  skip_back_ms: number | null;
  skip_forward_ms: number | null;
  // Automation rules (phase 7, supabase/migrations/003_automation_playlists.sql). Missing until 003 has
  // been run, and null when not set. See android/.../data/Automation.kt for the rules themselves.
  new_episode_action?: NewEpisodeAction | null;
  include_words?: string | null;
  exclude_words?: string | null;
  min_minutes?: number | null;
  max_minutes?: number | null;
  inbox_days?: number | null;
  inbox_keep?: number | null;
  /** Phase 10 (004_listening_upgrades.sql): trim ads stitched onto episodes, "start" | "end" | null. */
  ad_trim?: string | null;
  changed_at: number;
  rev?: number;
}

/** Where a podcast's new episodes go (the phone applies it when it finds them). */
export type NewEpisodeAction = "inbox" | "queue_next" | "queue_last" | "skip";

export const NEW_EPISODE_ACTIONS: { key: NewEpisodeAction; label: string }[] = [
  { key: "inbox", label: "New Episodes" },
  { key: "queue_next", label: "Top of queue (play next)" },
  { key: "queue_last", label: "End of queue" },
  { key: "skip", label: "Skip (don't add)" },
];

/** Choices for the tidy-up rules (0 = off) and length filters, as on the phone. */
export const INBOX_DAY_CHOICES = [0, 3, 7, 14, 30, 60];
export const INBOX_KEEP_CHOICES = [0, 1, 2, 3, 5, 10];
export const MINUTE_CHOICES = [5, 10, 15, 20, 30, 45, 60, 90, 120];

export interface EpisodeRow {
  id: string;
  feed_url: string;
  guid: string;
  title: string;
  description: string;
  pub_date: number;
  audio_url: string;
  mime_type: string | null;
  duration_ms: number;
  image_url: string | null;
  chapters_url: string | null;
  transcript_url: string | null;
  transcript_type: string | null;
  position_ms: number;
  played: boolean;
  last_played_at: number;
  in_inbox: boolean;
  queue_position: number | null;
  /** Phase 10 (004): unticked chapters' start times (ms), season and episode number. */
  skipped_chapters?: number[] | null;
  season?: number | null;
  episode_no?: number | null;
  changed_at: number;
  rev?: number;
}

export interface PlaybackDefaults {
  speed: number;
  smartSpeed: boolean;
  voiceBoost: boolean;
  skipBackMs: number;
  skipForwardMs: number;
  /** Tidy-up defaults (phase 7), synced in the same settings row: archive after N days / keep latest N; 0 = off. */
  inboxDays?: number;
  inboxKeep?: number;
  /** Phase 11: found ads are skipped ("auto"), get a Skip ad button ("button"), or are ignored ("off"). */
  adSkip?: string;
  /** Phase 13: back up a little when resuming after a pause (phone, car and web). */
  resumeRewind?: boolean;
}

export const DEFAULT_PLAYBACK: PlaybackDefaults = {
  speed: 1,
  smartSpeed: false,
  voiceBoost: false,
  skipBackMs: 15_000,
  skipForwardMs: 30_000,
  inboxDays: 0,
  inboxKeep: 0,
  adSkip: "auto",
  resumeRewind: true,
};

export const SPEEDS = [0.8, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.7, 2.0, 2.5, 3.0];
export const SKIPS_MS = [5_000, 10_000, 15_000, 30_000, 60_000, 300_000, 900_000];
