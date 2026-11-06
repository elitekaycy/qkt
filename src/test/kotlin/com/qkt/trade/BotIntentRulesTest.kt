package com.qkt.trade

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BotIntentRulesTest {
    private fun intent(
        limit: String? = null,
        stop: String? = null,
        sl: ExitSpec? = null,
        tp: ExitSpec? = null,
        exitAfter: String? = null,
    ) = BotIntent(
        side = Side.BUY,
        qktSymbol = "EXNESS:XAUUSD",
        lots = BigDecimal.ONE,
        limitPrice = limit?.let(::BigDecimal),
        stopPrice = stop?.let(::BigDecimal),
        sl = sl,
        tp = tp,
        exitAfter = exitAfter,
    )

    @Test
    fun `an entry cannot be both a limit and a stop`() {
        assertThatThrownBy { intent(limit = "2600", stop = "2700") }
            .hasMessage("entry cannot be both limit and stop")
    }

    @Test
    fun `lots must be positive`() {
        assertThatThrownBy { BotIntent(Side.SELL, "EXNESS:XAUUSD", lots = BigDecimal.ZERO) }
            .hasMessage("lots must be > 0: 0")
    }

    @Test
    fun `exit after takes a count and a unit`() {
        assertThat(intent(exitAfter = "4h").exitAfter).isEqualTo("4h")
        assertThatThrownBy { intent(exitAfter = "4 hours") }
            .hasMessage("--exit-after must be <n>s|m|h|d, got '4 hours'")
    }

    @Test
    fun `a trailing stop needs a take profit to ride as a bracket leg`() {
        val trail = ExitSpec.Trail(BigDecimal("30"), BigDecimal("10"))
        assertThatThrownBy { intent(sl = trail) }
            .hasMessage("a trailing stop is a bracket leg; pass --tp too")
        assertThat(intent(sl = trail, tp = ExitSpec.By(BigDecimal("60"))).sl).isEqualTo(trail)
    }

    @Test
    fun `a trailing stop parses its distance and optional profit threshold`() {
        assertThat(ExitSpec.parse("trail:30@10", allowRr = false))
            .isEqualTo(ExitSpec.Trail(BigDecimal("30"), BigDecimal("10")))
        assertThat(ExitSpec.parse("TRAIL:30", allowRr = false))
            .isEqualTo(ExitSpec.Trail(BigDecimal("30"), BigDecimal.ZERO))
    }

    @Test
    fun `a trailing take profit and malformed trails are refused`() {
        assertThatThrownBy { ExitSpec.parse("trail:30", allowRr = true) }
            .hasMessageContaining("a trailing take profit does not exist")
        assertThatThrownBy { ExitSpec.parse("trail:x", allowRr = false) }
            .hasMessage("invalid trail 'trail:x'")
        assertThatThrownBy { ExitSpec.parse("trail:30@y", allowRr = false) }
            .hasMessage("invalid trail 'trail:30@y'")
    }

    @Test
    fun `an unknown exit kind is refused by name`() {
        assertThatThrownBy { ExitSpec.parse("atr:2", allowRr = true) }
            .hasMessage("invalid exit spec 'atr:2': expected at:/by:/pct:/rr:/trail:")
    }

    @Test
    fun `a trailing stop renders as an armed trail bracket child`() {
        val source =
            renderBotStrategy(
                intent(sl = ExitSpec.Trail(BigDecimal("30.0"), BigDecimal("10")), tp = ExitSpec.By(BigDecimal("60"))),
            )

        assertThat(source).contains("BRACKET { STOP LOSS TRAILING 30 AFTER MFE >= 10, TAKE PROFIT BY 60 }")
    }
}
