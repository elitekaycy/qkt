package com.qkt.cli.daemon

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** What a deployed strategy states about its parameters and defaults in `strategy.started`. */
class StrategyMetadataPayloadTest {
    @Test
    fun `parameters keep their literal values and defaults name each configured kind`(
        @TempDir tmp: Path,
    ) {
        val source =
            """
            STRATEGY meta VERSION 3
            DEFAULTS {
                SIZING = 0.1
                STOP_LOSS = BY 5
                TAKE_PROFIT = RR 3.0
                TIF = GTC
            }
            SYMBOLS
                x = BACKTEST:X EVERY 1m
            PARAM fast = 9
            RULES
                WHEN x.close > fast THEN BUY x
            """.trimIndent()
        val file = tmp.resolve("meta.qkt").also { Files.writeString(it, source) }
        val ast = (Dsl.parse(source) as ParseResult.Success).value
        val factory =
            StrategyHandle.RealFactory(
                stateDir = StateDir.resolve(tmp.toString()),
                marketSourceProvider = { _: List<String> -> error("no feed") },
            )

        val metadata = factory.strategyMetadata("meta", file, ast, listOf("BACKTEST:X"), null)

        assertThat(metadata["deployName"]).isEqualTo("meta")
        assertThat(metadata["dslVersion"]).isEqualTo(3)
        assertThat(metadata["runtimeMode"]).isEqualTo("paper")
        assertThat((metadata["params"] as Map<*, *>)["fast"].toString()).isEqualTo("9")
        assertThat((metadata["defaults"] as Map<*, *>).keys).containsExactly("sizing", "tif", "stopLoss", "takeProfit")
    }
}
