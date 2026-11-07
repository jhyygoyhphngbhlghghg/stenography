package team.mikcoders.neurosteno.ui

import android.content.Context
import android.content.SharedPreferences
import team.mikcoders.neurosteno.export.Utterance

/**
 * Registry of custom display names for speakers.
 *
 * Maps a speaker_id (e.g. "speaker_1") to a user-chosen display name.
 * Names are persisted in SharedPreferences under "speaker_names".
 */
object SpeakerNames {

    private const val PREFS_NAME = "speaker_names"

    /** speaker_id -> display name. Only non-empty names are stored. */
    val names: MutableMap<String, String> = mutableMapOf()

    /** Returns the custom name if one is set, otherwise the speakerId itself. */
    fun displayName(speakerId: String): String =
        names[speakerId]?.takeIf { it.isNotEmpty() } ?: speakerId

    /** Sets a custom name for a speaker. An empty string clears the name. */
    fun setName(speakerId: String, name: String) {
        if (name.isEmpty()) {
            names.remove(speakerId)
        } else {
            names[speakerId] = name
        }
    }

    /** Extracts the unique speaker IDs from a transcript, sorted. */
    fun allSpeakers(transcript: List<Utterance>): List<String> =
        transcript.map { it.speakerId }.distinct().sorted()

    /** Saves all current names to SharedPreferences. */
    fun persist(context: Context) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            clear()
            for ((speakerId, name) in names) {
                putString(speakerId, name)
            }
            apply()
        }
    }

    /** Loads names from SharedPreferences, replacing the in-memory map. */
    fun load(context: Context) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        names.clear()
        for (entry in prefs.all) {
            val value = entry.value as? String ?: continue
            if (value.isNotEmpty()) {
                names[entry.key] = value
            }
        }
    }
}
