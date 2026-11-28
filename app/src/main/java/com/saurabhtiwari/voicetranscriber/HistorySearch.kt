package com.saurabhtiwari.voicetranscriber

/**
 * Search over "My recordings". A recording matches when its title matches, when its transcript is
 * stored on this device and matches, or when the server reported its meeting as a transcript match.
 */
object HistorySearch {
    /** Shorter queries match too much to be worth a server round trip. */
    const val MIN_REMOTE_QUERY_LENGTH = 3

    /**
     * @param deviceText transcript text stored on this device, keyed by local event id and by meeting id.
     * @param remoteMeetingIds meeting ids whose transcripts matched on the server.
     */
    fun filter(
        items: List<RecordingItem>,
        query: String,
        deviceText: Map<String, String>,
        remoteMeetingIds: Set<String>
    ): List<RecordingItem> {
        val q = query.trim()
        if (q.isEmpty()) return items
        return items.filter { matches(it, q, deviceText, remoteMeetingIds) }
    }

    fun matches(
        item: RecordingItem,
        query: String,
        deviceText: Map<String, String>,
        remoteMeetingIds: Set<String>
    ): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        if (item.title.contains(q, ignoreCase = true)) return true
        if (item.meetingId != null && item.meetingId in remoteMeetingIds) return true
        return textFor(item, deviceText)?.contains(q, ignoreCase = true) == true
    }

    /** A short excerpt around the first transcript hit, or null when the match is only in the title. */
    fun snippet(item: RecordingItem, query: String, deviceText: Map<String, String>, radius: Int = 48): String? {
        val q = query.trim()
        val text = textFor(item, deviceText) ?: return null
        if (q.isEmpty()) return null
        val index = text.indexOf(q, ignoreCase = true)
        if (index < 0) return null
        val start = (index - radius).coerceAtLeast(0)
        val end = (index + q.length + radius).coerceAtMost(text.length)
        val excerpt = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
        return (if (start > 0) "…" else "") + excerpt + (if (end < text.length) "…" else "")
    }

    /**
     * Turns user text into a PostgREST `ilike` value body: `%` and `_` would act as wildcards and
     * `*` is PostgREST's own wildcard, so they are neutralised; the caller adds the surrounding `*`.
     */
    fun escapeForIlike(term: String): String =
        term.trim()
            .replace("*", "")
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    private fun textFor(item: RecordingItem, deviceText: Map<String, String>): String? =
        deviceText[item.id] ?: item.meetingId?.let { deviceText[it] }
}
