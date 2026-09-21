package com.qkt.cli.status

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyStatusLinesHaltTest {
    private val halted = mutableListOf<String>()

    private fun render(json: String): Pair<String, List<String>> {
        val unhealthy = mutableListOf<String>()
        return renderStrategies(Json.parseToJsonElement(json).jsonArray, unhealthy, halted) to unhealthy
    }

    @Test
    fun `a halted strategy is shown with the command that clears it, and is not a health failure`() {
        val (screen, unhealthy) =
            render(
                """[{"name":"gold_trend","kind":"strategy","state":"running","trades":2,"uptimeMs":9000,
                    "halted":true,"haltReason":"LossStreakHalt[gold_trend]: 3 consecutive losses, max 3","haltScope":"PERSISTENT"}]""",
            )

        assertThat(
            screen,
        ).contains("[HALTED]").contains("halted: LossStreakHalt[gold_trend]: 3 consecutive losses, max 3")
        assertThat(halted)
            .singleElement()
            .asString()
            .contains("is halted")
            .contains("qkt resume gold_trend")
        assertThat(unhealthy).`as`("container health runs this command: a risk halt must not fail it").isEmpty()
    }

    @Test
    fun `a running strategy that is not halted stays healthy`() {
        val (screen, unhealthy) =
            render(
                """[{"name":"gold_trend","kind":"strategy","state":"running","trades":2,"uptimeMs":9000,"halted":false}]""",
            )

        assertThat(screen).doesNotContain("HALTED")
        assertThat(unhealthy).isEmpty()
    }
}
