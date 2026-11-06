package com.qkt.cli.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `GET /logs/{name}`: its query parameters, a stopped strategy's file, and following appended lines. */
class LogsRouteParamsTest : DaemonRouteFixture() {
    @TempDir lateinit var tmp: Path

    private val stateDir by lazy { StateDir.resolve(tmp.toString()) }

    private fun writeLog(
        name: String,
        vararg lines: String,
    ): Path {
        val file = stateDir.logFile(name)
        Files.createDirectories(file.parent)
        Files.write(file, lines.toList())
        return file
    }

    @Test
    fun `a blank name is refused and an unknown name without a file is not found`() {
        val p = plane(registry(stateDir), stateDir)

        assertThat(get(p, "/logs/").first).isEqualTo(400)
        assertThat(get(p, "/logs/ghost")).isEqualTo(404 to """{"error":"unknown strategy: ghost"}""")
    }

    @Test
    fun `a strategy that logged nothing yet answers an empty body`() {
        val registry = registry(stateDir).also { it.deploy("a", tmp.resolve("a.qkt")) }
        Files.deleteIfExists(stateDir.logFile("a"))

        assertThat(get(plane(registry, stateDir), "/logs/a")).isEqualTo(200 to "")
    }

    @Test
    fun `a stopped strategy's log file is still served from the state directory`() {
        writeLog("gone", "first", "second", "third")

        assertThat(get(plane(registry(stateDir), stateDir), "/logs/gone?lines=2")).isEqualTo(200 to "second\nthird\n")
    }

    @Test
    fun `negative lines and an unparsable since are refused`() {
        writeLog("gone", "x")
        val p = plane(registry(stateDir), stateDir)

        assertThat(get(p, "/logs/gone?lines=-1")).isEqualTo(400 to """{"error":"invalid 'lines' query param"}""")
        assertThat(get(p, "/logs/gone?since=yesterday")).isEqualTo(400 to """{"error":"invalid 'since' query param"}""")
    }

    @Test
    fun `since keeps lines from that instant and lines without a timestamp`() {
        writeLog(
            "gone",
            "2026-01-01T00:00:00Z old",
            "2026-01-02T00:00:00,500Z new",
            "continuation without a stamp",
        )

        val (_, body) = get(plane(registry(stateDir), stateDir), "/logs/gone?since=2026-01-02T00:00:00Z")

        assertThat(body).isEqualTo("2026-01-02T00:00:00,500Z new\ncontinuation without a stamp\n")
    }

    @Test
    fun `follow streams lines appended after the tail`() {
        val file = writeLog("gone", "before")
        val p = plane(registry(stateDir), stateDir)
        val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        val call =
            client.newCall(
                Request.Builder().url("http://127.0.0.1:${p.boundPort}/logs/gone?follow=true").build(),
            )

        call.execute().use { resp ->
            val source = resp.body!!.source()
            assertThat(source.readUtf8LineStrict()).isEqualTo("before")
            Files.writeString(file, "after\n", StandardOpenOption.APPEND)
            var line = source.readUtf8LineStrict()
            while (line.isEmpty()) line = source.readUtf8LineStrict()
            assertThat(line).isEqualTo("after")
            call.cancel()
        }
    }
}
