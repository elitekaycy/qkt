package com.qkt.cli

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.TickEvent
import com.qkt.marketdata.Tick
import com.qkt.observe.EngineAuditJournal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AuditSequenceRestartTest {
    private val clock = FixedClock(time = START_MS)

    @Test
    fun `a first session on an empty journal starts at zero`(
        @TempDir tmp: Path,
    ) {
        val journal = EngineAuditJournal(tmp.resolve("state/audit-journal"), "alpha", clock)

        assertThat(journal.lastSequence()).isNull()
        journal.close()
    }

    @Test
    fun `a restarted session resumes the audit sequence after the last journaled event`(
        @TempDir tmp: Path,
    ) {
        val root = tmp.resolve("state/audit-journal")
        runSession(root, ticks = 3)
        val restarted = EngineAuditJournal(root, "alpha", clock)
        assertThat(restarted.lastSequence()).isEqualTo(2L)
        restarted.close()

        runSession(root, ticks = 2)

        val sequences =
            Files
                .readAllLines(root.resolve("alpha/audit-2026-08-09.jsonl"))
                .filter { it.isNotBlank() }
                .map { SEQ.find(it)!!.groupValues[1].toLong() }
        assertThat(sequences).containsExactly(0L, 1L, 2L, 3L, 4L)
    }

    @Test
    fun `a journal written before sequences resumed yields its highest sequence, not its last`(
        @TempDir tmp: Path,
    ) {
        val root = tmp.resolve("state/audit-journal")
        val file = root.resolve("alpha/audit-2026-08-09.jsonl")
        Files.createDirectories(file.parent)
        val legacyRuns = (0L..5L) + (0L..2L)
        Files.writeString(file, legacyRuns.joinToString("") { """{"v":1,"ts":$START_MS,"seq":$it}""" + "\n" })

        val journal = EngineAuditJournal(root, "alpha", clock)

        assertThat(journal.lastSequence()).isEqualTo(5L)
        journal.close()
    }

    @Test
    fun `a golden capture spanning a restart materializes`(
        @TempDir tmp: Path,
    ) {
        val root = tmp.resolve("state/audit-journal")
        runSession(root, ticks = 3)
        runSession(root, ticks = 3)
        val transport = tmp.resolve("state/mt5-transport-journal/demo/transport-2026-08-09.jsonl")
        Files.createDirectories(transport.parent)
        Files.writeString(
            transport,
            """{"v":1,"ts":${START_MS + 1_500L},"method":"GET","path":"/account","responseCode":200}""" + "\n",
        )
        val bundle = tmp.resolve("restart-golden.zip")

        val captured =
            GoldenCommand(
                Args(
                    arrayOf(
                        "golden",
                        "capture",
                        "--session",
                        "alpha",
                        "--state-dir",
                        tmp.toString(),
                        "--out",
                        bundle.toString(),
                        "--read-only",
                    ),
                ),
            ).run()
        val materialized =
            GoldenCommand(
                Args(
                    arrayOf(
                        "golden",
                        "materialize",
                        "--bundle",
                        bundle.toString(),
                        "--out",
                        tmp.resolve("replay").toString(),
                    ),
                ),
            ).run()

        assertThat(captured).isEqualTo(ExitCodes.SUCCESS)
        assertThat(materialized).isEqualTo(ExitCodes.SUCCESS)
    }

    private fun runSession(
        root: Path,
        ticks: Int,
    ) {
        val journal = EngineAuditJournal(root, "alpha", clock)
        val bus = EventBus(clock, MonotonicSequenceGenerator.resumingAfter(journal.lastSequence()))
        bus.subscribeAllFirst { journal.append(it) }
        repeat(ticks) {
            clock.time += 1_000L
            bus.publish(
                TickEvent(
                    Tick(
                        symbol = "EXNESS:EURUSD",
                        price = Money.of("1.16235"),
                        timestamp = clock.time,
                        bid = Money.of("1.16224"),
                        ask = Money.of("1.16246"),
                    ),
                    timestamp = clock.time,
                ),
            )
        }
        journal.close()
    }

    private companion object {
        const val START_MS = 1_786_233_600_000L
        val SEQ = Regex("\"seq\":(\\d+)")
    }
}
