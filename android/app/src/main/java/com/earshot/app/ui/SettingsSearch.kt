package com.earshot.app.ui

/**
 * Phase 22: Settings search. Each group of the Settings page with the words people might look for in it
 * (the row titles plus a few other words). A search shows only the groups that match every word typed.
 */
object SettingsSearch {
    val GROUPS: Map<String, List<String>> = linkedMapOf(
        "Sync" to listOf("account", "sign in", "sign out", "password", "email", "sync now"),
        "Appearance" to listOf("theme", "glow", "classic", "true black", "dark", "text size", "font", "bigger", "compact", "comfortable", "swipe", "new episodes"),
        "Playback" to listOf("speed", "smart speed", "silence", "voice boost", "loudness", "skip back", "skip forward", "per output", "earbuds", "headphones", "speaker", "car", "continue between phone and web", "handoff", "rewind", "pause", "sleep timer", "automatic sleep", "when the queue ends", "playlist", "per-speaker", "speaker levels", "lab", "guest", "host"),
        "Time saved" to listOf("time saved", "saved", "smart speed"),
        "Automation" to listOf("archive", "keep per podcast", "tidy", "new episodes"),
        "Downloads" to listOf("download", "wi-fi", "wifi", "mobile data", "queued", "delete", "storage", "space", "limit", "expire"),
        "Background" to listOf("keep earshot running", "battery", "sleeping apps", "optimisation", "optimization"),
        "Notifications" to listOf("notifications", "new episodes", "push", "pinned"),
        "AI from transcripts" to listOf("ai", "ads", "found ads", "skip ad", "transcripts"),
        "Car, backup and updates" to listOf("android auto", "car", "set up", "backup", "back up", "restore", "updates", "check for updates", "beta"),
        "About" to listOf("version", "what's new", "report a problem", "bug", "log"),
    )

    /** The groups to show for [query]; all of them when it's blank. */
    fun groups(query: String): Set<String> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return GROUPS.keys
        return GROUPS.filter { (name, terms) ->
            val hay = (listOf(name.lowercase()) + terms).joinToString(" | ")
            words.all { hay.contains(it) }
        }.keys
    }
}
