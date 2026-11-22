package com.qkt.cli.observe

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `GET /events` against an exchange with no socket: the frames written, and what a failing reader ends. */
class SseEventsRouteTest {
    /** Accepts [allowed] writes, then fails every one after. */
    private class FailingAfter(
        private val allowed: Int,
    ) : OutputStream() {
        val written = ByteArrayOutputStream()
        private var writes = 0

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            if (writes++ >= allowed) throw IOException("reader gone")
            written.write(b, off, len)
        }
    }

    @Test
    fun `an idle stream sends keep-alives until the wait is interrupted`() {
        val out = ByteArrayOutputStream()
        var pauses = 0

        Routes.events(EventRing(8), keepAlivePause = { if (pauses++ == 1) throw InterruptedException() }).handle(
            FakeHttpExchange("GET", out),
        )

        assertThat(Thread.interrupted()).isTrue()
        assertThat(out.toString()).isEqualTo(": connected\n\n: keep-alive\n\n")
    }

    @Test
    fun `a reader that is gone before the first frame ends the stream`() {
        val out = FailingAfter(allowed = 0)

        Routes.events(EventRing(8), keepAlivePause = { error("no keep-alive expected") }).handle(
            FakeHttpExchange("GET", out),
        )

        assertThat(out.written.size()).isZero()
    }

    @Test
    fun `an event the reader cannot take ends the stream at the next look`() {
        val out = FailingAfter(allowed = 1)
        val ring = EventRing(8)

        Routes.events(ring, keepAlivePause = { ring.append("trade", buildJsonObject { put("qty", "1") }) }).handle(
            FakeHttpExchange("GET", out),
        )

        assertThat(out.written.toString()).isEqualTo(": connected\n\n")
    }
}
