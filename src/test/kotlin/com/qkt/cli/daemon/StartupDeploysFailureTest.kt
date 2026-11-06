package com.qkt.cli.daemon

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A `--load-dir` file that cannot deploy says why, so the retrier and the operator see the cause. */
class StartupDeploysFailureTest {
    private val registry = StrategyRegistry(StrategyHandle.Factory { name, _, _ -> error("no session for $name") })

    @Test
    fun `a file that does not parse names the file and the line`(
        @TempDir tmp: Path,
    ) {
        val file = tmp.resolve("broken.qkt").also { Files.writeString(it, "STRATEGY nope") }

        assertThatThrownBy { deployLoadDirFile("broken", file, registry, null) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("parse failure for $file:")
        assertThat(registry.list()).isEmpty()
    }

    @Test
    fun `a portfolio file without a portfolio deployer is refused`(
        @TempDir tmp: Path,
    ) {
        Files.writeString(
            tmp.resolve("child.qkt"),
            "STRATEGY child VERSION 1\nSYMBOLS\n    x = BACKTEST:X EVERY 1m\nRULES\n    WHEN x.close > 0 THEN BUY x SIZING 1\n",
        )
        val book =
            tmp.resolve("book.qkt").also {
                Files.writeString(
                    it,
                    "PORTFOLIO book VERSION 1 CAPITAL 10000\nSYMBOLS\n    x = BACKTEST:X EVERY 1m\n" +
                        "IMPORT 'child.qkt' AS child\nRULES\n    RUN child WEIGHT 1\n",
                )
            }

        assertThatThrownBy { deployLoadDirFile("book", book, registry, null) }
            .hasMessage("portfolio deploy not configured on this daemon")
    }

    @Test
    fun `a sample built from a long carries its value as text`() {
        val sample = PrometheusFormat.Sample(mapOf("strategy" to "a"), 5L)

        assertThat(sample.value).isEqualTo("5")
        assertThat(sample.labels).containsEntry("strategy", "a")
    }
}
