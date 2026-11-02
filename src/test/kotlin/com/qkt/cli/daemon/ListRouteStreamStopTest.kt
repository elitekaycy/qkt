package com.qkt.cli.daemon

import com.qkt.broker.StreamStop
import java.nio.file.Path
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ListRouteStreamStopTest : ListRouteFixture() {
    private fun listBody(
        tmp: Path,
        stops: List<StreamStop>,
    ): String {
        val registry = StrategyRegistry(stubFactory(StateDir.resolve(tmp.toString()), streamStops = stops))
        val plane = ControlPlane(registry, port = 0)
        plane.start()
        opened.add(plane)
        registry.deploy("alpha", tmp.resolve("a.qkt"))
        return OkHttpClient()
            .newCall(Request.Builder().url("http://127.0.0.1:${plane.boundPort}/list").build())
            .execute()
            .body!!
            .string()
    }

    @Test
    fun `a stop is listed with its stream and reason, escaped, beside a state that stays running`(
        @TempDir tmp: Path,
    ) {
        val body = listBody(tmp, listOf(StreamStop("s", "D:BTC@front", "roll \"failed\"")))

        assertThat(body).contains(""""state":"running"""")
        assertThat(body).contains(
            """"streamStops":[{"strategyId":"s","stream":"D:BTC@front","reason":"roll \"failed\""}]""",
        )
    }

    @Test
    fun `a strategy with no stop has a list row with no new field`(
        @TempDir tmp: Path,
    ) {
        assertThat(listBody(tmp, emptyList())).doesNotContain("streamStops")
    }
}
