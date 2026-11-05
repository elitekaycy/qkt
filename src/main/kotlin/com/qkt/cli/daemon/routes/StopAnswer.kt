package com.qkt.cli.daemon.routes

import com.qkt.app.FlattenResult
import com.qkt.app.LiveSessionHandle

/** The session's stop flatten; one that threw is an unconfirmed flatten, never a flat one. */
internal fun LiveSessionHandle.verifiedStopFlatten(): FlattenResult =
    runCatching { flattenForStop() }
        .getOrElse { FlattenResult(verifiedFlat = false, detail = "flatten failed: ${it.message}") }

/**
 * The answer to `POST /stop/{name}`: [state] with `flat` when any flatten was confirmed ([flats], by strategy), or
 * `not_flat` with what is `held` and the `detail` of why, when one did not end flat. The strategy no longer trades either way.
 */
internal fun stopAnswer(
    name: String,
    state: String,
    trades: Int,
    flats: List<Pair<String, FlattenResult>>,
): String {
    val notFlat = flats.filterNot { it.second.verifiedFlat }
    if (notFlat.isEmpty()) {
        val flat = if (flats.isEmpty()) "" else ""","flat":true"""
        return """{"name":${jsonString(name)},"state":${jsonString(state)},"trades":$trades$flat}"""
    }
    val held = notFlat.flatMap { it.second.remainingTickets }.joinToString(",") { jsonString(it) }
    val detail =
        notFlat.joinToString("; ") { (who, result) ->
            val why = result.detail ?: "not confirmed flat"
            if (who == name) why else "$who: $why"
        }
    return """{"name":${jsonString(name)},"state":"not_flat","trades":$trades,"flat":false,""" +
        """"held":[$held],"detail":${jsonString(detail)}}"""
}
