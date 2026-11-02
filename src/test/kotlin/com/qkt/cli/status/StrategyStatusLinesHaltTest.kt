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

    @Test
    fun `an audit journal that lost a decision event makes the strategy unhealthy`() {
        val (screen, unhealthy) =
            render(
                """[{"name":"opt","kind":"strategy","state":"running","trades":0,"uptimeMs":9000,"halted":false,
                    "auditJournal":{"written":9,"queued":0,"droppedMarketData":120,"lostDecisionEvents":1,
                    "omittedChainQuotes":50000}}]""",
            )

        assertThat(screen).contains("audit journal: dropped 120 market-data record(s), lost 1 decision event(s)")
        assertThat(unhealthy).singleElement().asString().contains("audit journal lost 1 decision event(s)")
    }

    @Test
    fun `an audit journal that omitted chain quotes by design is healthy and quiet`() {
        val (screen, unhealthy) =
            render(
                """[{"name":"opt","kind":"strategy","state":"running","trades":0,"uptimeMs":9000,"halted":false,
                    "auditJournal":{"written":9,"queued":0,"droppedMarketData":0,"lostDecisionEvents":0,
                    "omittedChainQuotes":50000}}]""",
            )

        assertThat(screen).doesNotContain("audit journal")
        assertThat(unhealthy).isEmpty()
    }
}
