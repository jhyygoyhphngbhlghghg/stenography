package team.mikcoders.neurosteno.export

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** On-device diary: transcripts + summaries kept in filesDir/diary so a demo survives process death. Offline only. */
@Serializable
data class DiaryEntry(
    @SerialName("created_ms") val createdMs: Long,
    @SerialName("title") val title: String,
    @SerialName("summary") val summary: String = "",
    @SerialName("transcript") val transcript: List<Utterance>,
)

object Diary {
    private val json = Json { prettyPrint = true; explicitNulls = false }

    private fun dir(ctx: Context): File {
        val d = File(ctx.filesDir, "diary")
        d.mkdirs()
        return d
    }

    fun save(ctx: Context, entry: DiaryEntry): File {
        val f = File(dir(ctx), "d-%d.json".format(entry.createdMs))
        f.writeText(json.encodeToString(DiaryEntry.serializer(), entry))
        return f
    }

    fun list(ctx: Context): List<DiaryEntry> = dir(ctx).listFiles { f -> f.extension == "json" }
        ?.mapNotNull { f ->
            runCatching { json.decodeFromString(DiaryEntry.serializer(), f.readText()) }.getOrNull()
        }
        ?.sortedByDescending { it.createdMs }
        .orEmpty()

    fun delete(ctx: Context, createdMs: Long) {
        File(dir(ctx), "d-%d.json".format(createdMs)).delete()
    }
}
