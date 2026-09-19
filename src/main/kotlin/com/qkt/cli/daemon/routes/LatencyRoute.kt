package com.qkt.cli.daemon.routes

import com.qkt.cli.daemon.StrategyRegistry
import com.sun.net.httpserver.HttpExchange

/**
 * `GET /latency` — aggregates per-strategy `/latency` responses into a top-level
 * `{ "<strategyName>": <perStrategyLatencyJson>, ... }`. Each entry's body is whatever
 * the strategy's [com.qkt.cli.observe.Routes.latency] handler returned (already JSON).
 * Strategies that can't be reached are omitted from the aggregate.
 */

internal fun handleLatencyAll(
    ex: HttpExchange,
    registry: StrategyRegistry,
) {
    val parts =
        registry.list().mapNotNull { h ->
            val body = fetchStrategyEndpoint(h.port, "/latency") ?: return@mapNotNull null
            """"${h.name}":$body"""
        }
    respond(ex, 200, parts.joinToString(separator = ",", prefix = "{", postfix = "}"))
}
