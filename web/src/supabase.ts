import { createClient } from "@supabase/supabase-js";
import { SUPABASE_KEY, SUPABASE_URL } from "./config";

// Stays signed in (session kept in this browser's local storage, refreshed automatically).
export const supabase = createClient(SUPABASE_URL, SUPABASE_KEY, {
  auth: { persistSession: true, autoRefreshToken: true },
});

/** Calls an edge function as the signed-in user; throws with the function's own error message. */
export async function callFunction<T>(name: string, body: Record<string, unknown>): Promise<T> {
  const { data, error } = await supabase.functions.invoke(name, { body });
  if (error) {
    let msg = error.message;
    const ctx = (error as { context?: Response }).context;
    if (ctx && typeof ctx.json === "function") {
      try {
        msg = ((await ctx.json()) as { error?: string }).error ?? msg;
      } catch {
        // Keep the general message.
      }
    }
    throw new Error(msg);
  }
  return data as T;
}
