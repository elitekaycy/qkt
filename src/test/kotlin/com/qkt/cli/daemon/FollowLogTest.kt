package com.qkt.cli.daemon

import com.qkt.cli.daemon.routes.followLog
import com.qkt.cli.daemon.routes.shutdownAfterGrace
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FollowLogTest {
    @TempDir
    lateinit var tmp: Path

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

    private fun log(text: String) = Files.writeString(tmp.resolve("s.log"), text)

    @Test
    fun `a follower gets what is appended, starts over when the file shrinks, and is sent blanks while idle`() {
        val file = log("abc\n")
        val out = FailingAfter(allowed = 2)
        val steps =
            ArrayDeque<() -> Unit>().apply {
                add { Files.writeString(file, "x\n") }
                add { Files.writeString(file, "y\n", StandardOpenOption.APPEND) }
                add { }
            }

        followLog(file, out) { steps.removeFirst()() }

        assertThat(out.written.toString()).isEqualTo("x\ny\n")
    }

    @Test
    fun `a line written after the start size is streamed even when it landed before the first look`() {
        val file = log("abc\n")
        val startSize = Files.size(file)
        Files.writeString(file, "new\n", StandardOpenOption.APPEND)
        val out = FailingAfter(allowed = 1)

        followLog(file, out, startSize) {}

        assertThat(out.written.toString()).isEqualTo("new\n")
    }

    @Test
    fun `a follower that cannot take new bytes ends the stream`() {
        val file = log("abc\n")
        val out = FailingAfter(allowed = 0)

        followLog(file, out) { Files.writeString(file, "more\n", StandardOpenOption.APPEND) }

        assertThat(out.written.size()).isZero()
    }

    @Test
    fun `an interrupt while waiting to shut down still shuts down and keeps the interrupt`() {
        var shutDown = false

        shutdownAfterGrace({ shutDown = true }, grace = { throw InterruptedException() })

        assertThat(shutDown).isTrue()
        assertThat(Thread.interrupted()).isTrue()
    }

    @Test
    fun `a shutdown that throws is contained`() {
        shutdownAfterGrace({ error("already closing") }, grace = {})
    }
}
