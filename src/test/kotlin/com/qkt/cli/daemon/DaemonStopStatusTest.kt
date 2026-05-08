package com.qkt.cli.daemon

import com.qkt.cli.Args
import com.qkt.cli.DaemonCommand
import com.qkt.cli.ExitCodes
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DaemonStopStatusTest {
    private class IdleFeed : TickFeed {
        private val gate = CountDownLatch(1)

        override fun next(): Tick? {
            gate.await(30, TimeUnit.SECONDS)
            return null
        }

        override fun close() {
            gate.countDown()
        }
    }

    private class IdleSource : MarketSource {
        override val name: String = "Idle"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed = IdleFeed()
    }

    private fun waitForFile(
        path: Path,
        deadlineMs: Long = 5_000L,
    ) {
        val end = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < end) {
            if (Files.exists(path)) return
            Thread.sleep(50)
        }
        throw AssertionError("file did not appear in $deadlineMs ms: $path")
    }

    @Test
    fun `POST shutdown returns 202 then daemon exits`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val daemonThread =
            Thread {
                val args = Args(arrayOf("daemon", "--state-dir", tmp.toString()))
                DaemonCommand(args, sourceFactory = { IdleSource() }).run()
            }
        daemonThread.isDaemon = true
        daemonThread.start()
        try {
            waitForFile(stateDir.controlPortFile)
            val port = stateDir.readControlPort()!!
            val client = OkHttpClient()
            val resp =
                client
                    .newCall(
                        Request
                            .Builder()
                            .url("http://127.0.0.1:$port/shutdown")
                            .post("".toRequestBody("application/json".toMediaType()))
                            .build(),
                    ).execute()
            assertThat(resp.code).isEqualTo(202)
            assertThat(resp.body!!.string()).contains("\"status\":\"accepted\"")
            daemonThread.join(5_000)
            assertThat(daemonThread.isAlive).isFalse
            assertThat(Files.exists(stateDir.controlPortFile)).isFalse
        } finally {
            if (daemonThread.isAlive) daemonThread.interrupt()
        }
    }

    @Test
    fun `qkt daemon stop tells running daemon to shut down`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val daemonThread =
            Thread {
                val args = Args(arrayOf("daemon", "--state-dir", tmp.toString()))
                DaemonCommand(args, sourceFactory = { IdleSource() }).run()
            }
        daemonThread.isDaemon = true
        daemonThread.start()
        waitForFile(stateDir.controlPortFile)

        val out = ByteArrayOutputStream()
        val origOut = System.out
        System.setOut(PrintStream(out, true))
        val code =
            try {
                val args = Args(arrayOf("daemon", "stop", "--state-dir", tmp.toString()))
                DaemonCommand(args).run()
            } finally {
                System.setOut(origOut)
            }
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(out.toString()).contains("daemon stop accepted")
        daemonThread.join(5_000)
        assertThat(daemonThread.isAlive).isFalse
    }

    @Test
    fun `qkt daemon status prints control port and ok health`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val daemonThread =
            Thread {
                val args = Args(arrayOf("daemon", "--state-dir", tmp.toString()))
                DaemonCommand(args, sourceFactory = { IdleSource() }).run()
            }
        daemonThread.isDaemon = true
        daemonThread.start()
        try {
            waitForFile(stateDir.controlPortFile)
            val port = stateDir.readControlPort()!!
            val out = ByteArrayOutputStream()
            val origOut = System.out
            System.setOut(PrintStream(out, true))
            val code =
                try {
                    val args = Args(arrayOf("daemon", "status", "--state-dir", tmp.toString()))
                    DaemonCommand(args).run()
                } finally {
                    System.setOut(origOut)
                }
            assertThat(code).isEqualTo(ExitCodes.SUCCESS)
            val output = out.toString()
            assertThat(output).contains("control port: $port")
            assertThat(output).contains("\"status\":\"ok\"")
        } finally {
            daemonThread.interrupt()
            daemonThread.join(5_000)
        }
    }

    @Test
    fun `qkt daemon stop fails with no daemon running`(
        @TempDir tmp: Path,
    ) {
        val err = ByteArrayOutputStream()
        val origErr = System.err
        System.setErr(PrintStream(err, true))
        val code =
            try {
                val args = Args(arrayOf("daemon", "stop", "--state-dir", tmp.toString()))
                DaemonCommand(args).run()
            } finally {
                System.setErr(origErr)
            }
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(err.toString()).contains("no daemon running")
    }
}
