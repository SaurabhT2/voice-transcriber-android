package com.saurabhtiwari.voicetranscriber

/** Find-in-transcript helpers: where matches are, how many, and which lines to keep when filtering. */
object TranscriptFind {
    /** Non-overlapping, case-insensitive match ranges of [query] in [text]; empty for a blank query. */
    fun ranges(text: String, query: String): List<IntRange> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val found = ArrayList<IntRange>()
        var from = 0
        while (from <= text.length - q.length) {
            val index = text.indexOf(q, from, ignoreCase = true)
            if (index < 0) break
            found.add(index until index + q.length)
            from = index + q.length
        }
        return found
    }

    fun contains(text: String, query: String): Boolean {
        val q = query.trim()
        return q.isNotEmpty() && text.contains(q, ignoreCase = true)
    }

    fun count(turns: List<TranscriptTurn>, query: String): Int = turns.sumOf { ranges(it.text, query).size }

    /** For transcripts without segments: the sentences or lines that contain [query], in order. */
    fun matchingSentences(text: String, query: String): List<String> {
        if (query.isBlank()) return emptyList()
        return text.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && contains(it, query) }
    }
}
