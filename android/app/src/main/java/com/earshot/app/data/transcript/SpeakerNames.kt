package com.earshot.app.data.transcript

import android.content.Context
import androidx.core.content.edit
import com.earshot.app.data.feed.Person
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Phase 18: names for a transcript's speaker labels ("Speaker 0" from Deepgram). You can name each one;
 * until you do, the episode's podcast:person list gives a guess (shown with a "?") when it has exactly as
 * many people as the transcript has speakers: hosts first, then guests, in the order they first speak.
 * Kept on this phone only for now.
 */
object SpeakerNames {
    private const val PREFS = "speaker_names"

    private val _version = MutableStateFlow(0)
    /** Changes whenever a name is saved, so screens can read the names again. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun names(context: Context, episodeId: String): Map<String, String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(episodeId, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.getString(it) }.filterValues { it.isNotBlank() }
        }.getOrDefault(emptyMap())
    }

    /** Saves [name] for [speaker] in [episodeId] (blank clears it). */
    fun set(context: Context, episodeId: String, speaker: String, name: String) {
        val now = names(context, episodeId).toMutableMap()
        if (name.isBlank()) now.remove(speaker) else now[speaker] = name.trim().take(60)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            if (now.isEmpty()) remove(episodeId) else putString(episodeId, JSONObject(now as Map<*, *>).toString())
        }
        _version.value++
    }

    /** Speaker labels in the order they first speak. */
    fun speakers(segments: List<Segment>): List<String> = segments.mapNotNull { it.speaker }.distinct()

    /** The guess from podcast:person: only when the counts match (and there are at least two). */
    fun guess(speakers: List<String>, people: List<Person>): Map<String, String> {
        val ordered = people.filter { it.role == "host" } + people.filter { it.role == "guest" }
        if (speakers.size < 2 || ordered.size != speakers.size || ordered.size != people.size) return emptyMap()
        return speakers.zip(ordered.map { it.name }).toMap()
    }

    /** What to show for [speaker]: your name, else the guess with "?", else "Speaker N". */
    fun label(speaker: String, mine: Map<String, String>, guessed: Map<String, String>): String =
        mine[speaker] ?: guessed[speaker]?.let { "$it?" } ?: TranscriptFormat.speakerName(speaker)
}
