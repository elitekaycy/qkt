package com.qkt.cli

import com.qkt.cli.observe.GateStatus
import com.qkt.cli.observe.ObserveRunner
import com.qkt.cli.observe.PlacementSchedule
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ObserveCommandTest {
    @Test
    fun `produces a clean run — placement passes and pnl reviews`() {
        val status =
            """{"strategy":"hedge-straddle","version":1,"uptimeMs":1,"startedAt":"2026-06-04T00:00:00Z",""" +
                """"equity":15,"balance":15,"realized":15,"unrealized":0,"positions":[],"lastTrade":null}"""
        val logs =
            "2026-06-04T06:55:01.000 [INFO] submit Stop dsl-h--1 X BUY p\n" +
                "2026-06-04T08:00:00.000 [INFO] trade SELL X qty=0.2 px=1 realized=15"
        val report =
            ObserveRunner.run(
                from = Instant.parse("2026-06-04T06:00:00Z"),
                to = Instant.parse("2026-06-04T09:00:00Z"),
                schedule = PlacementSchedule(setOf(6), 55),
                logsText = logs,
                statusJson = status,
            )
        assertThat(report.single { it.name == "placement" }.status).isEqualTo(GateStatus.PASS)
        assertThat(report.single { it.name == "errors" }.status).isEqualTo(GateStatus.PASS)
        assertThat(report.single { it.name == "pnl" }.status).isEqualTo(GateStatus.REVIEW)
    }
}
