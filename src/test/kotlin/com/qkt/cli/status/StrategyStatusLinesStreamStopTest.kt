package com.qkt.cli.status

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyStatusLinesStreamStopTest {
    @Test
    fun `a stopped stream is named with its reason and how to clear it, and is not a health failure`() {
        val unhealthy = mutableListOf<String>()
        val halted = mutableListOf<String>()
        val stopped = mutableListOf<String>()

        val screen =
            renderStrategies(
                Json
                    .parseToJsonElement(
                        """[{"name":"hold","kind":"strategy","state":"running","trades":0,"uptimeMs":9000,"halted":false,
                        "streamStops":[{"strategyId":"s","stream":"D:BTC@front","reason":"roll failed"}]}]""",
                    ).jsonArray,
                unhealthy,
                halted,
                stopped,
            )

        assertThat(screen).contains("[STREAM_STOPPED]").contains("stream stopped: D:BTC@front: roll failed")
        assertThat(stopped)
            .singleElement()
            .asString()
            .contains("D:BTC@front")
            .contains("qkt resume hold")
        assertThat(halted).isEmpty()
        assertThat(unhealthy).isEmpty()
    }
}
