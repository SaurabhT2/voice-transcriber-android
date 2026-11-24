package com.saurabhtiwari.voicetranscriber

import org.json.JSONObject

internal fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() && it != "null" }
