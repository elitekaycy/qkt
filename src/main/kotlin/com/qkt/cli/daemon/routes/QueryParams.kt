package com.qkt.cli.daemon.routes

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Decodes a raw query string into a map; parameters without `=` are dropped. */
internal fun parseQuery(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw
        .split('&')
        .mapNotNull { part ->
            val i = part.indexOf('=')
            if (i < 0) return@mapNotNull null
            urlDecode(part.substring(0, i)) to urlDecode(part.substring(i + 1))
        }.toMap()
}

private fun urlDecode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)
