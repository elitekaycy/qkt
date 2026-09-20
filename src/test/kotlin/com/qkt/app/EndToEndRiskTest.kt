package com.qkt.app

import com.qkt.common.Money
import com.qkt.events.RiskRejectedEvent
import com.qkt.marketdata.Tick
import com.qkt.risk.RiskRule
import com.qkt.risk.rules.MaxPositionSize
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EndToEndRiskTest : EndToEndFixtures() {
    @Test
    fun `risk approved order produces a fill and updates positions`() {
        val rules = listOf<RiskRule>(MaxPositionSize("XAUUSD", maxQty = Money.of("5")))
        val strategy = buyEveryTick("XAUUSD")
        wirePipeline(listOf(strategy), rules = rules)

        engine.onTick(Tick("XAUUSD", Money.of("2400.0"), 999L))

        assertThat(trades).hasSize(1)
        assertThat(positions.positionFor("XAUUSD")?.quantity).isEqualByComparingTo(Money.of("1"))
    }

    @Test
    fun `risk rejected order publishes RiskRejectedEvent and skips broker`() {
        val rejections = mutableListOf<RiskRejectedEvent>()
        bus.subscribe<RiskRejectedEvent> { rejections.add(it) }

        val rules = listOf<RiskRule>(MaxPositionSize("XAUUSD", maxQty = Money.of("0.5")))
        val strategy = buyEveryTick("XAUUSD")
        wirePipeline(listOf(strategy), rules = rules)

        engine.onTick(Tick("XAUUSD", Money.of("2400.0"), 999L))

        assertThat(trades).isEmpty()
        assertThat(rejections).hasSize(1)
        assertThat(rejections[0].request.symbol).isEqualTo("XAUUSD")
        assertThat(rejections[0].reason).contains("MaxPositionSize")
    }
}
