package com.qkt.trade.session

import com.qkt.common.Side
import com.qkt.execution.Trade
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LiveSessionRecordTest {
    @TempDir
    lateinit var tmp: Path

    private fun trade(
        id: String,
        side: Side,
        price: String,
    ) = Trade(id, "EXNESS:XAUUSD", BigDecimal(price), BigDecimal("0.10"), side, 1_000L)

    @Test
    fun `each booked fill is one json line under its identity`() {
        val dir = tmp.resolve("run/a")
        val record = LiveSessionRecord(dir)

        record.fill(trade("o1", Side.BUY, "2400.5"), BigDecimal.ZERO, "brain")
        record.fill(trade("o2", Side.SELL, "2410.5"), BigDecimal("100.00"), "brain")

        val lines = Files.readAllLines(dir.resolve("fills.jsonl"))
        assertThat(lines).containsExactly(
            """{"identity":"brain","orderId":"o1","symbol":"EXNESS:XAUUSD","side":"BUY",""" +
                """"qty":0.10,"price":2400.5,"realized":0,"timeMs":1000}""",
            """{"identity":"brain","orderId":"o2","symbol":"EXNESS:XAUUSD","side":"SELL",""" +
                """"qty":0.10,"price":2410.5,"realized":100.00,"timeMs":1000}""",
        )
    }

    @Test
    fun `the summary sums fills and realized per identity with that identity's open legs`() {
        val record = LiveSessionRecord(tmp)
        record.fill(trade("o1", Side.BUY, "2400"), BigDecimal("-5"), "brain")
        record.fill(trade("o2", Side.BUY, "2400"), BigDecimal("15"), "brain")
        val leg = SessionLeg("brain", "EXNESS:XAUUSD", "BUY", BigDecimal("0.10"), BigDecimal("2400"), "77", "leg-1")
        val other = SessionLeg("hedge", "EXNESS:XAUUSD", "SELL", BigDecimal("0.10"), BigDecimal("2401"), null, "leg-2")

        val path = record.summary(listOf("brain", "idle"), listOf(leg, other))

        assertThat(path).isEqualTo(tmp.resolve("summary.json"))
        val text = Files.readString(path)
        assertThat(text)
            .startsWith("""{"identities":[{"identity":"brain","fills":2,"realized":10,"openLegs":[{""")
            .contains(""""ticket":"77"""")
            .contains("""{"identity":"idle","fills":0,"realized":0,"openLegs":[]}""")
            .doesNotContain("leg-2")
    }

    @Test
    fun `journals present in the session are copied to the report and missing ones skipped`() {
        val session = tmp.resolve("session")
        Files.createDirectories(session)
        Files.writeString(session.resolve("fills.jsonl"), "f\n")
        Files.writeString(session.resolve("intents.jsonl"), "i\n")
        val out = tmp.resolve("out")
        Files.createDirectories(out)
        Files.writeString(out.resolve("fills.jsonl"), "stale\n")

        copySessionJournals(session, out)

        assertThat(Files.readString(out.resolve("fills.jsonl"))).isEqualTo("f\n")
        assertThat(Files.readString(out.resolve("intents.jsonl"))).isEqualTo("i\n")
        assertThat(out.resolve("summary.json")).doesNotExist()
    }
}
