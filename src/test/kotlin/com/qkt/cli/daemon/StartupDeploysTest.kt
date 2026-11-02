package com.qkt.cli.daemon

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StartupDeploysTest {
    private val strategy =
        """
        STRATEGY one VERSION 1

        SYMBOLS
            btc = BACKTEST:BTCUSDT EVERY 1m

        RULES
            WHEN btc.close > 100
            THEN BUY btc SIZING 1
        """.trimIndent()

    @Test
    fun `the pass is underway through every deploy, a failed one too, and reports its end once`(
        @TempDir tmp: Path,
    ) {
        Files.writeString(tmp.resolve("one.qkt"), strategy)
        Files.writeString(tmp.resolve("two.qkt"), strategy)
        val deploys = StartupDeploys(tmp.toString())
        val seen = mutableListOf<Boolean>()
        val registry =
            StrategyRegistry(
                StrategyHandle.Factory { _, _, _ ->
                    seen += deploys.underway()
                    error("venue down")
                },
            )
        var ends = 0
        assertThat(deploys.underway()).isTrue()

        val failed = deploys.run(registry, onFinished = { ends++ })

        assertThat(seen).containsExactly(true, true)
        assertThat(failed).hasSize(2)
        assertThat(deploys.underway()).isFalse()
        assertThat(ends).isEqualTo(1)
    }

    @Test
    fun `a daemon without a load dir has no pass underway and still reports its end`() {
        val deploys = StartupDeploys(null)
        var ends = 0

        val failed =
            deploys.run(StrategyRegistry(StrategyHandle.Factory { _, _, _ -> error("unused") }), onFinished = {
                ends++
            })

        assertThat(deploys.underway()).isFalse()
        assertThat(failed).isEmpty()
        assertThat(ends).isEqualTo(1)
    }

    @Test
    fun `a load dir that is no directory ends the pass`(
        @TempDir tmp: Path,
    ) {
        val deploys = StartupDeploys(tmp.resolve("missing").toString())

        deploys.run(StrategyRegistry(StrategyHandle.Factory { _, _, _ -> error("unused") }))

        assertThat(deploys.underway()).isFalse()
    }
}
