package com.qkt.marketdata

import com.qkt.common.Money
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TickAssemblerTest {
    private fun bd(s: String) = BigDecimal(s).setScale(Money.SCALE, Money.ROUNDING)

    @Test
    fun `derives mid price from bid and ask when price absent`() {
        val tick =
            TickAssembler.assemble(
                symbol = "XAUUSD",
                timestamp = 1_712_000_000_000L,
                price = null,
                volume = null,
                bid = bd("1711.50400000"),
                ask = bd("1712.00200000"),
                bidVolume = bd("0.00012000"),
                askVolume = bd("0.00018000"),
                location = { "test:1" },
            )
        assertEquals(bd("1711.75300000"), tick.price)
        assertEquals(bd("1711.50400000"), tick.bid)
        assertEquals(bd("1712.00200000"), tick.ask)
    }

    @Test
    fun `keeps explicit price over mid`() {
        val tick =
            TickAssembler.assemble(
                "EURUSD",
                1L,
                price = bd("1.10000000"),
                volume = null,
                bid = bd("1.09000000"),
                ask = bd("1.11000000"),
                bidVolume = null,
                askVolume = null,
                location = { "test:1" },
            )
        assertEquals(bd("1.10000000"), tick.price)
    }

    @Test
    fun `rejects row with neither price nor bid-ask`() {
        assertThrows(IllegalStateException::class.java) {
            TickAssembler.assemble("X", 1L, null, null, null, null, null, null, { "test:1" })
        }
    }

    @Test
    fun `rejects bid greater than ask`() {
        assertThrows(IllegalStateException::class.java) {
            TickAssembler.assemble("X", 1L, null, null, bd("2.0"), bd("1.0"), null, null, { "test:1" })
        }
    }
}
