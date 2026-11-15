package com.qkt.app

import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StrategyIdValidationTest {
    private val idle =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    @Test
    fun `a repeated strategy id is refused and the message lists every id`() {
        assertThatThrownBy { requireValidStrategyIds(listOf("gold" to idle, "silver" to idle, "gold" to idle)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Strategy IDs must be unique: [gold, silver, gold]")
    }

    @Test
    fun `a blank strategy id is refused`() {
        assertThatThrownBy { requireValidStrategyIds(listOf("gold" to idle, " " to idle)) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Strategy ID must be non-blank")
    }

    @Test
    fun `distinct named strategies pass`() {
        assertThatCode { requireValidStrategyIds(listOf("gold" to idle, "silver" to idle)) }.doesNotThrowAnyException()
    }
}
