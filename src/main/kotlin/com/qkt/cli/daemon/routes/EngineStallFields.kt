package com.qkt.cli.daemon.routes

import com.qkt.app.EngineStalls
import kotlinx.serialization.builtins.serializer

/**
 * A strategy's `/health` engine-load fields: `"engineStalls"`, how many messages held its engine thread a second or
 * more, and `"engineWorstHold"`, the longest of them (`{"ms":1234,"what":"event OrderFilled"}`, null before any). Each
 * field follows `"inboundQueueDepth"`, so the string starts with what it adds and ends with a comma.
 */
internal fun renderEngineStallFields(stalls: EngineStalls): String {
    val worst =
        stalls.worstHold?.let {
            """{"ms":${stalls.worstHoldMs},"what":${routeJson.encodeToString(String.serializer(), it)}}"""
        } ?: "null"
    return """"engineStalls":${stalls.count},"engineWorstHold":$worst,"""
}
