package com.qkt.cli

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestContextScenarioStreamsTest {
    private fun parse(
        path: Path,
        symbol: String,
    ) = Files
        .writeString(
            path,
            "STRATEGY s VERSION 1\nSYMBOLS\n    x = BACKTEST:$symbol EVERY 1m\nRULES\n    WHEN x.close > 0 THEN BUY x SIZING 0.1\n",
        ).let { (Dsl.parseFile(it) as ParseResult.Success).value }

    @Test
    fun `a scenario strategy over other streams than the base is refused`(
        @TempDir dir: Path,
    ) {
        val base = parse(dir.resolve("base.qkt"), "XAUUSD")
        val other = parse(dir.resolve("other.qkt"), "EURUSD")
        val argv =
            arrayOf(
                "backtest",
                "base.qkt",
                "--from",
                "2026-06-04",
                "--to",
                "2026-06-05",
                "--data-root",
                dir.resolve("data").toString(),
                "--no-fetch",
            )
        val ctx = BacktestContext.build(Args(argv), base, fetcherOverride = null)

        assertThatThrownBy { ctx.backtest(emptyMap(), ast = other) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("scenario strategy must declare the same streams as the base; the shared feed is keyed to them")
    }
}
