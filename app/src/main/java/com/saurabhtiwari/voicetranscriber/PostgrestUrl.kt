package com.saurabhtiwari.voicetranscriber

import java.net.URLEncoder

/** Builds PostgREST query URLs with every key and value percent-encoded. */
object PostgrestUrl {
    fun build(endpoint: String, vararg params: Pair<String, String>): String {
        if (params.isEmpty()) return endpoint
        return endpoint + "?" + params.joinToString("&") { (key, value) -> encode(key) + "=" + encode(value) }
    }

    // URLEncoder targets HTML forms (space -> "+"); in a URL query PostgREST expects "%20".
    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}
