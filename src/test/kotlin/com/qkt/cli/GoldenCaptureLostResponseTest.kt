package com.qkt.cli

import com.qkt.common.FixedClock
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * On a loaded gateway the response to an order can be lost: the engine records the request, calls
 * the outcome UNKNOWN, asks the venue, and books the fill it finds. The attestation's order cases
 * hit this and could not be captured, because the capture wanted a 2xx on the placement.
 */
class GoldenCaptureLostResponseTest {
    private fun session(
        tmp: Path,
        placement: String,
        filled: Boolean,
    ) {
        val state = tmp.resolve("state")
        val audit = state.resolve("audit-journal/alpha/audit-2026-09-21.jsonl")
        val transport = state.resolve("mt5-transport-journal/demo/transport-2026-09-21.jsonl")
        audit.parent.let(Files::createDirectories)
        transport.parent.let(Files::createDirectories)
        val fill =
            """{"v":1,"ts":5000,"seq":2,"eventType":"com.qkt.events.BrokerEvent.OrderFilled","orderId":"o-1",""" +
                """"symbol":"EXNESS:BTCUSD","fill":{"side":"BUY","price":"86900","quantity":"0.01","brokerOrderId":"42","partial":false}}"""

        fun tick(
            ts: Int,
            seq: Int,
        ) = """{"v":1,"ts":$ts,"seq":$seq,"eventType":"com.qkt.events.TickEvent","symbol":"EXNESS:BTCUSD",""" +
            """"tick":{"timestampMs":$ts,"price":"86900"}}"""
        Files.writeString(
            audit,
            listOfNotNull(tick(1000, 1), fill.takeIf { filled }, tick(6000, 3)).joinToString("\n") + "\n",
        )
        Files.writeString(transport, placement + "\n")
    }

    private fun capture(tmp: Path): Int =
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
                    tmp.resolve("g.zip").toString(),
                ),
            ),
            clock = FixedClock(1_790_023_750_000L),
        ).run()

    private val lost =
        """{"v":1,"ts":1600,"seq":1,"profile":"demo","method":"POST","path":"/order","engineOrderId":"o-1","requestBody":"{}"}"""

    @Test
    fun `a placement whose response was lost, and which the venue shows filled, is captured`() {
        session(tmp, lost, filled = true)

        assertThat(capture(tmp)).isEqualTo(ExitCodes.SUCCESS)
    }

    @Test
    fun `a lost response for an order that never filled is not evidence of a placement`() {
        session(tmp, lost, filled = false)

        assertThat(capture(tmp)).isNotEqualTo(ExitCodes.SUCCESS)
    }

    @Test
    fun `a placement the gateway refused is still not evidence, even if the id later fills`() {
        val refused =
            """{"v":1,"ts":1600,"seq":1,"profile":"demo","method":"POST","path":"/order","engineOrderId":"o-1",""" +
                """"requestBody":"{}","responseCode":400,"responseBody":"{}"}"""
        session(tmp, refused, filled = true)

        assertThat(capture(tmp)).isNotEqualTo(ExitCodes.SUCCESS)
    }

    @TempDir
    lateinit var tmp: Path
}
