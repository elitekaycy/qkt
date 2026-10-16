package com.qkt.backtest.report

import com.qkt.events.RiskEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HaltsJsonTest {
    @Test
    fun `each halt keeps its time, strategy, scope and reason in firing order`() {
        val halts =
            listOf(
                RiskEvent.Halted("daily loss 88.27 exceeds max 60", "gold", scope = "DAILY", timestamp = 10),
                RiskEvent.Halted("drawdown \"5%\"", null, timestamp = 20),
            )

        assertThat(HaltsJson.render(halts)).isEqualTo(
            "[{\"timestampMs\": 10, \"strategyId\": \"gold\", \"scope\": \"DAILY\", " +
                "\"reason\": \"daily loss 88.27 exceeds max 60\"}," +
                "{\"timestampMs\": 20, \"strategyId\": null, \"scope\": null, \"reason\": \"drawdown \\\"5%\\\"\"}]",
        )
    }

    @Test
    fun `a run without halts writes an empty list`() {
        assertThat(HaltsJson.render(emptyList())).isEqualTo("[]")
    }
}
