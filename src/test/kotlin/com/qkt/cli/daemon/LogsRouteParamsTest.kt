package com.qkt.cli.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSource
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

        follow("/logs/gone?follow=true") { source ->
            assertThat(source.readUtf8LineStrict()).isEqualTo("before")
            Files.writeString(file, "after\n", StandardOpenOption.APPEND)
            assertThat(nextLineOtherThan(source, "")).isEqualTo("after")
        }
    }

    @Test
    fun `follow sends a line appended while the tail is still being written`() {
        // 20 MB of tail is more than loopback socket buffers hold, so the server is still writing the tail when
        // the line is appended; the follow must start from the bytes the tail read, not the size after it.
        val wide = "x".repeat(1023)
        val file = writeLog("gone", *Array(20_000) { wide })

        follow("/logs/gone?lines=20000&follow=true") { source ->
            assertThat(source.readUtf8LineStrict()).isEqualTo(wide)
            Files.writeString(file, "after\n", StandardOpenOption.APPEND)
            assertThat(nextLineOtherThan(source, wide)).isEqualTo("after")
        }
    }

    private fun follow(
        path: String,
        read: (BufferedSource) -> Unit,
    ) {
        val p = plane(registry(stateDir), stateDir)
        val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
        val call = client.newCall(Request.Builder().url("http://127.0.0.1:${p.boundPort}$path").build())
        call.execute().use { resp ->
            read(resp.body!!.source())
            call.cancel()
        }
    }

    /** The next line that is neither a keepalive nor [skip]; keepalives arrive every 500 ms, so bound the wait. */
    private fun nextLineOtherThan(
        source: BufferedSource,
        skip: String,
    ): String {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < end) {
            val line = source.readUtf8LineStrict()
            if (line.isNotEmpty() && line != skip) return line
        }
        return "<no new line within 15 s>"
    }
}
