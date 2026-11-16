package com.qkt.connector.mt5

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Request bodies carry exactly the optional fields that were set; recovery reads refuse bad retry settings. */
class MT5RequestJsonFieldsTest {
    @Test
    fun `an order body carries every optional field that is set`() {
        val body =
            orderJson(
                MT5OrderRequest(
                    symbol = "XAUUSDm",
                    volume = BigDecimal("0.10"),
                    type = "BUY_STOP_LIMIT",
                    price = BigDecimal("2400"),
                    sl = BigDecimal("2390"),
                    tp = BigDecimal("2420"),
                    stopLimit = BigDecimal("2399.5"),
                    slDistance = 150L,
                    deviation = 7,
                    magic = 9,
                    comment = "c1",
                    expiration = 1_800_000_000L,
                ),
            )

        assertThat(body).isEqualTo(
            """{"symbol":"XAUUSDm","volume":0.10,"type":"BUY_STOP_LIMIT","price":2400,"sl":2390,"tp":2420,""" +
                """"stoplimit":2399.5,"sl_distance":150,"deviation":7,"magic":9,"client_order_id":"c1",""" +
                """"expiration":1800000000,"comment":"c1"}""",
        )
    }

    @Test
    fun `an order body omits every optional field that is unset`() {
        val body =
            orderJson(
                MT5OrderRequest(symbol = "XAUUSDm", volume = BigDecimal.ONE, type = "BUY", magic = 9, comment = "c1"),
            )

        assertThat(body).isEqualTo(
            """{"symbol":"XAUUSDm","volume":1,"type":"BUY","deviation":20,"magic":9,"client_order_id":"c1","comment":"c1"}""",
        )
    }

    @Test
    fun `an order modification carries only the fields being changed`() {
        val all =
            MT5OrderModification(
                BigDecimal("1.5"),
                BigDecimal("1.4"),
                BigDecimal("1.6"),
                slDistance = 20L,
                expiration = 99L,
            )

        assertThat(
            orderModificationJson(all),
        ).isEqualTo("""{"price":1.5,"sl":1.4,"tp":1.6,"sl_distance":20,"expiration":99}""")
        assertThat(orderModificationJson(MT5OrderModification())).isEqualTo("{}")
        assertThat(orderModificationJson(MT5OrderModification(tp = BigDecimal("1.6")))).isEqualTo("""{"tp":1.6}""")
    }

    @Test
    fun `close and protection bodies name the ticket and only the levels given`() {
        assertThat(closePositionJson(7L, null)).isEqualTo("""{"position":{"ticket":7}}""")
        assertThat(closePositionJson(7L, BigDecimal("0.20"))).isEqualTo("""{"position":{"ticket":7,"volume":0.20}}""")
        assertThat(partialCloseJson(7L, BigDecimal("0.05"))).isEqualTo("""{"ticket":7,"volume":0.05}""")
        assertThat(modifyPositionJson(7L, null, null)).isEqualTo("""{"position":7}""")
        assertThat(
            modifyPositionJson(7L, BigDecimal("1.1"), BigDecimal("1.3")),
        ).isEqualTo("""{"position":7,"sl":1.1,"tp":1.3}""")
    }

    @Test
    fun `a recovery read refuses a retry count or backoff that makes no sense`() {
        assertThatThrownBy { readMT5RecoverySnapshot(0, 0L, { _, _ -> }, { emptyList() }, { emptyList() }) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("attempts must be > 0: 0")
        assertThatThrownBy { readMT5RecoverySnapshot(1, -1L, { _, _ -> }, { emptyList() }, { emptyList() }) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("backoffMs must be >= 0: -1")
    }

    @Test
    fun `a recovery read names each failed read by its error or as unavailable`() {
        val reasons = mutableListOf<String>()

        assertThatThrownBy {
            readMT5RecoverySnapshot(
                attempts = 2,
                backoffMs = 0L,
                onFailedAttempt = { attempt, reason -> reasons += "$attempt $reason" },
                readPendingOrders = { error("gateway down") },
                readPositions = { if (reasons.isEmpty()) null else throw IllegalStateException() },
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("MT5 recovery venue reads failed 2 times: pending orders: gateway down, positions: unavailable")
        assertThat(reasons).containsExactly(
            "1 pending orders: gateway down, positions: unavailable",
            "2 pending orders: gateway down, positions: unavailable",
        )
    }
}
