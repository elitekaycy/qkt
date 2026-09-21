package com.qkt.observe.insights

import com.qkt.events.RiskEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RiskHaltEnvelopeTest {
    @Test
    fun `a halt that is not strategy-scoped names the strategies of the session it stopped`() {
        val halted =
            com.qkt.events.RiskEvent
                .Halted("operator", null, scope = "PERSISTENT", timestamp = 1L)
        val resumed =
            com.qkt.events.RiskEvent
                .Resumed(null, timestamp = 2L)

        assertThat(InsightsTranslate.fromRiskHalted(halted, listOf("gold_trend")).toJson("qkt-prod"))
            .contains(""""sessionStrategies":["gold_trend"]""")
        assertThat(InsightsTranslate.fromRiskResumed(resumed, listOf("gold_trend")).toJson("qkt-prod"))
            .contains(""""sessionStrategies":["gold_trend"]""")
        val scoped =
            com.qkt.events.RiskEvent
                .Halted("LossStreak", "gold_trend", scope = "DAILY", timestamp = 1L)
        assertThat(InsightsTranslate.fromRiskHalted(scoped, listOf("gold_trend")).toJson("qkt-prod"))
            .`as`("a strategy-scoped halt already says whose it is")
            .doesNotContain("sessionStrategies")
    }

    @Test
    fun `a halt tells the dashboard its scope and whether only resume clears it`() {
        val persistent =
            com.qkt.events.RiskEvent
                .Halted("operator", "gold_trend", scope = "PERSISTENT", timestamp = 1L)
        val daily =
            com.qkt.events.RiskEvent
                .Halted("DailyLoss", "gold_trend", scope = "DAILY", timestamp = 1L)

        assertThat(InsightsTranslate.fromRiskHalted(persistent).toJson("qkt-prod"))
            .contains(""""scope":"PERSISTENT"""")
            .contains(""""persistent":true""")
        assertThat(InsightsTranslate.fromRiskHalted(daily).toJson("qkt-prod"))
            .contains(""""scope":"DAILY"""")
            .contains(""""persistent":false""")
    }
}
