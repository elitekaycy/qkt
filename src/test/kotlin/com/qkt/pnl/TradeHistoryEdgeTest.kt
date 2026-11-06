package com.qkt.pnl

import com.qkt.persistence.NoopStatePersistor
import com.qkt.persistence.PersistedTradeHistory
import com.qkt.persistence.PersistedTradeOutcome
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TradeHistoryEdgeTest {
    @Test
    fun `a history must keep at least one trade`() {
        assertThatThrownBy { TradeHistory(maxHistory = 0) }.hasMessage("TradeHistory.maxHistory must be > 0: 0")
    }

    @Test
    fun `a strategy with no trades reads empty everywhere`() {
        val h = TradeHistory()

        assertThat(h.lastTradeAt("s")).isNull()
        assertThat(h.lastTradePnl("s")).isNull()
        assertThat(h.lastTradeAtFor("s", "X")).isNull()
        assertThat(h.banked("s")).isEqualByComparingTo("0")
        assertThat(h.winStreak("s")).isZero()
        assertThat(h.tradesSince("s", 0L)).isZero()
    }

    @Test
    fun `an unbroken win run banks every trade and counts the whole streak`() {
        val h = TradeHistory()
        h.recordTrade("s", 1L, BigDecimal("2"), "X")
        h.recordTrade("s", 2L, BigDecimal("3"), "X")

        assertThat(h.banked("s")).isEqualByComparingTo("5")
        assertThat(h.winStreak("s")).isEqualTo(2)
        assertThat(h.lossStreak("s")).isZero()
        assertThat(h.lastTradeAtFor("s", "Y")).isNull()
        assertThat(h.lastTradeAtFor("s", "X")).isEqualTo(2L)
    }

    @Test
    fun `a restore skips flat outcomes, keeps the newest within the cap and ignores a blank strategy`() {
        val persistor = NoopStatePersistor()
        persistor.saveTradeHistory(
            "s",
            PersistedTradeHistory(
                listOf(
                    PersistedTradeOutcome(1L, BigDecimal("-1"), "X"),
                    PersistedTradeOutcome(2L, BigDecimal.ZERO, "X"),
                    PersistedTradeOutcome(3L, BigDecimal("4"), "X"),
                    PersistedTradeOutcome(4L, BigDecimal("5"), "Y"),
                ),
            ),
        )
        val h = TradeHistory(maxHistory = 3, persistor = persistor)

        h.restore("s")
        h.restore(" ")
        h.restore("unknown")

        assertThat(h.tradesSince("s", 0L)).isEqualTo(2)
        assertThat(h.lastTradePnl("s")).isEqualByComparingTo("5")
        assertThat(h.lossesSince("s", 0L)).isZero()
        assertThat(h.lastTradeAt("unknown")).isNull()
    }
}
