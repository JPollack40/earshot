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
  changed_at: number;
  rev?: number;
}

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
  changed_at: number;
  rev?: number;
}

export interface PlaybackDefaults {
  speed: number;
  smartSpeed: boolean;
  voiceBoost: boolean;
  skipBackMs: number;
  skipForwardMs: number;
}

export const DEFAULT_PLAYBACK: PlaybackDefaults = {
  speed: 1,
  smartSpeed: false,
  voiceBoost: false,
  skipBackMs: 15_000,
  skipForwardMs: 30_000,
};

export const SPEEDS = [0.8, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.7, 2.0, 2.5, 3.0];
export const SKIPS_MS = [5_000, 10_000, 15_000, 30_000, 60_000, 300_000, 900_000];
