package com.saurabhtiwari.voicetranscriber

import org.json.JSONArray
import org.json.JSONObject

/** Everything needed to render one meeting as shareable text. */
data class ExportDocument(
    val title: String,
    val createdAt: String,
    val details: List<String>,
    val transcript: DisplayTranscript,
    /** Used when the transcript has no timestamped segments (e.g. a plain on-device transcript). */
    val plainText: String,
    val notes: JSONObject?
)

/** Markdown and plain-text renderings of a meeting's transcript and notes. */
object TranscriptExport {
    fun documentFor(
        meeting: MeetingDetail,
        aliases: Map<String, String>,
        notes: JSONObject?
    ) = ExportDocument(
        title = meeting.title,
        createdAt = meeting.createdAt,
        details = listOfNotNull(meeting.source, meeting.language, meeting.model).filter { it.isNotBlank() },
        transcript = DisplayTranscripts.build(meeting.segments, meeting.speakers, aliases),
        plainText = meeting.transcript,
        notes = notes
    )

    fun documentFor(record: LocalMeetingRecord) = ExportDocument(
        title = record.title,
        createdAt = record.createdAt,
        details = listOfNotNull(record.language?.takeIf { it.isNotBlank() }, "on-device transcript"),
        transcript = DisplayTranscript(emptyList(), emptyList()),
        plainText = record.transcript,
        notes = null
    )

    fun markdown(doc: ExportDocument): String = render(doc, markdown = true)

    fun plainText(doc: ExportDocument): String = render(doc, markdown = false)

    /** A file-system-safe name such as `Weekly sync.md`. */
    fun fileName(title: String, extension: String): String {
        val cleaned = title.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").replace(Regex("\\s+"), " ").trim().trim('.', '_', ' ')
        return (cleaned.ifEmpty { "meeting" }.take(80).trimEnd('.', ' ', '_').ifEmpty { "meeting" }) + "." + extension
    }

    private fun render(doc: ExportDocument, markdown: Boolean): String {
        val out = StringBuilder()
        fun heading(text: String) {
            out.append(if (markdown) "## $text\n\n" else text.uppercase() + "\n")
            if (!markdown) out.append("-".repeat(text.length)).append("\n")
        }
        fun bullet(text: String) = out.append(if (markdown) "- " else "• ").append(text).append("\n")

        val title = doc.title.replace(Regex("\\s+"), " ").trim().ifEmpty { "Untitled meeting" }
        out.append(if (markdown) "# $title\n\n" else "$title\n${"=".repeat(title.length)}\n\n")
        val meta = listOfNotNull(dateOf(doc.createdAt)) + doc.details
        if (meta.isNotEmpty()) out.append(if (markdown) "*${meta.joinToString(" · ")}*\n\n" else meta.joinToString(" · ") + "\n\n")

        doc.notes?.let { appendNotes(out, it, markdown, ::heading, ::bullet) }

        heading("Transcript")
        if (!doc.transcript.isEmpty) {
            val withSpeakers = doc.transcript.hasSpeakers
            doc.transcript.turns.forEach { turn ->
                val time = SpeakerTranscripts.formatTimestamp(turn.startSeconds)
                val who = turn.label ?: if (withSpeakers) "Unlabeled" else null
                val header = if (who != null) (if (markdown) "**$who** · $time" else "$who [$time]") else "[$time]"
                out.append(header).append("\n").append(turn.text).append("\n\n")
            }
        } else {
            out.append(doc.plainText.trim().ifEmpty { "Transcript unavailable." }).append("\n")
        }
        return out.toString().trimEnd() + "\n"
    }

    private fun appendNotes(
        out: StringBuilder,
        notes: JSONObject,
        markdown: Boolean,
        heading: (String) -> Unit,
        bullet: (String) -> StringBuilder
    ) {
        val summary = notes.optString("executive_summary").trim()
        if (summary.isNotEmpty()) {
            heading("Summary")
            out.append(summary).append("\n\n")
        }
        section(out, heading, bullet, "Key points", notes.optJSONArray("key_points")) { it.text() }
        section(out, heading, bullet, "Decisions", notes.optJSONArray("decisions")) { item ->
            item.text() + item.optNullableString("rationale")?.let { " — $it" }.orEmpty()
        }
        section(out, heading, bullet, "Action items", notes.optJSONArray("action_items")) { item ->
            val details = listOfNotNull(
                item.optNullableString("owner")?.let { "owner: $it" },
                item.optNullableString("due_date")?.let { "due: $it" }
            )
            val box = if (markdown) "[ ] " else ""
            box + item.text() + if (details.isEmpty()) "" else " (${details.joinToString(", ")})"
        }
        section(out, heading, bullet, "Open questions", notes.optJSONArray("open_questions")) { it.text() }
        val participants = notes.optJSONArray("participants")
        if (participants != null && participants.length() > 0) {
            heading("Participants")
            for (i in 0 until participants.length()) participants.optString(i).takeIf { it.isNotBlank() }?.let { bullet(it) }
            out.append("\n")
        }
    }

    private fun section(
        out: StringBuilder,
        heading: (String) -> Unit,
        bullet: (String) -> StringBuilder,
        title: String,
        values: JSONArray?,
        render: (JSONObject) -> String
    ) {
        if (values == null) return
        val lines = (0 until values.length()).mapNotNull { values.optJSONObject(it) }
            .filter { it.text().isNotEmpty() }
            .map { item -> stamp(item) + render(item) }
        if (lines.isEmpty()) return
        heading(title)
        lines.forEach { bullet(it) }
        out.append("\n")
    }

    /** `[1:05] ` when the note point carries a start time, so it can be located in the audio. */
    private fun stamp(item: JSONObject): String {
        if (item.isNull("start_seconds") || !item.has("start_seconds")) return ""
        val seconds = item.optDouble("start_seconds", Double.NaN)
        return if (seconds.isNaN()) "" else "[" + SpeakerTranscripts.formatTimestamp(seconds) + "] "
    }

    private fun JSONObject.text(): String = optString("text").ifBlank { optString("task") }.trim()

    private fun dateOf(createdAt: String): String? =
        Regex("^\\d{4}-\\d{2}-\\d{2}").find(createdAt)?.value
}
