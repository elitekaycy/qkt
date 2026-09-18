package com.qkt.cli.daemon.routes

import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyRegistry
import com.sun.net.httpserver.HttpExchange
import java.nio.file.Files
import java.time.Instant

/**
 * `GET /logs/{name}` — tail of the strategy's log file as plain text, filtered by
 * `lines` and `since`; `follow=true` keeps streaming appended bytes until the client leaves.
 */
internal fun handleLogs(
    ex: HttpExchange,
    registry: StrategyRegistry,
    stateDir: StateDir?,
    path: String,
) {
    val name = path.removePrefix("/logs/").trim('/').ifBlank { null }
    if (name == null) return respond(ex, 400, """{"error":"missing strategy name in path"}""")
    val handle = registry.get(name)
    val logFile =
        handle?.logFile
            ?: stateDir?.logFile(name)?.takeIf { Files.exists(it) }
            ?: return respond(ex, 404, """{"error":"unknown strategy: $name"}""")
    if (!Files.exists(logFile)) {
        // Stream nothing rather than 404; the strategy may simply have produced no logs yet.
        ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        ex.sendResponseHeaders(200, 0)
        ex.responseBody.use { /* no-op */ }
        return
    }
    val params = parseQuery(ex.requestURI.rawQuery)
    val lines = params["lines"]?.toIntOrNull() ?: 200
    if (lines < 0) return respond(ex, 400, """{"error":"invalid 'lines' query param"}""")
    val since: Instant? =
        params["since"]?.let {
            runCatching { Instant.parse(it) }.getOrNull()
                ?: return respond(ex, 400, """{"error":"invalid 'since' query param"}""")
        }
    val follow = params["follow"] == "true"

    ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
    ex.responseHeaders.add("Cache-Control", "no-cache")
    ex.sendResponseHeaders(200, 0)
    val out = ex.responseBody
    val all = Files.readAllLines(logFile)
    val filtered =
        if (since != null) {
            all.filter { line -> parseTimestamp(line)?.isAfter(since.minusSeconds(1)) ?: true }
        } else {
            all
        }
    val tail = filtered.takeLast(lines)
    try {
        for (line in tail) {
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
        }
        out.flush()
        if (follow) {
            var lastSize = Files.size(logFile)
            while (true) {
                Thread.sleep(500)
                val sz = Files.size(logFile)
                if (sz < lastSize) {
                    // file truncated; reset
                    lastSize = 0
                }
                if (sz == lastSize) {
                    runCatching {
                        out.write("\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                    }.onFailure { return }
                    continue
                }
                Files
                    .newInputStream(logFile)
                    .use { input ->
                        // skip() may skip fewer bytes than asked; loop so we don't re-send
                        // already-streamed log bytes to the follower.
                        var toSkip = lastSize
                        while (toSkip > 0) {
                            val skipped = input.skip(toSkip)
                            if (skipped <= 0) break
                            toSkip -= skipped
                        }
                        val bytes = input.readBytes()
                        try {
                            out.write(bytes)
                            out.flush()
                        } catch (_: java.io.IOException) {
                            return
                        }
                    }
                lastSize = sz
            }
        }
    } catch (_: java.io.IOException) {
        // client disconnected
    } finally {
        runCatching { out.close() }
    }
}

private fun parseTimestamp(line: String): Instant? {
    // Logback emits ISO8601 with `T` replaced by space; both are accepted by Instant.parse via fallback.
    val first = line.substringBefore(' ', missingDelimiterValue = "")
    if (first.isBlank()) return null
    return runCatching { Instant.parse(first.replace(',', '.')) }.getOrNull()
}
