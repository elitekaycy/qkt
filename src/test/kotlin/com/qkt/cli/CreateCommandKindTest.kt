package com.qkt.cli

import com.qkt.dsl.parse.Lexer
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.Parser
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CreateCommandKindTest : CreateCommandFixture() {
    @Test
    fun `--kind minimal scaffolds the no-broker tree without mt5 gateway`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("project")
        val (code, _, _) = invoke("create", "template", target.toString(), "--kind", "minimal")
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)

        for (entry in MINIMAL_EXPECTED_FILES) {
            assertThat(target.resolve(entry))
                .withFailMessage("expected $entry at $target")
                .exists()
        }
        val compose = Files.readString(target.resolve("docker-compose.yml"))
        assertThat(compose).contains("stop_grace_period: 30s")
        assertThat(compose)
            .withFailMessage("minimal compose should not declare mt5-gateway")
            .doesNotContain("mt5-gateway")
        val makefile = Files.readString(target.resolve("Makefile"))
        assertThat(makefile)
            .withFailMessage("minimal Makefile should not declare audit-ticks target")
            .doesNotContain("audit-ticks")
        assertThat(makefile).contains("resync-dry-run")
        assertThat(makefile).contains("qkt resync /strategies/$(STRAT).qkt --as $(STRAT)")
        assertThat(makefile).contains("qkt reconcile $(STRAT)")
    }

    @Test
    fun `--kind bybit scaffolds the no-gateway tree wired for Bybit REST`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("project")
        val (code, _, _) = invoke("create", "template", target.toString(), "--kind", "bybit")
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)

        for (entry in BYBIT_EXPECTED_FILES) {
            assertThat(target.resolve(entry))
                .withFailMessage("expected $entry at $target")
                .exists()
        }
        val compose = Files.readString(target.resolve("docker-compose.yml"))
        assertThat(compose).contains("stop_grace_period: 30s")
        assertThat(compose)
            .withFailMessage("bybit compose should not declare mt5-gateway")
            .doesNotContain("mt5-gateway")
        assertThat(compose)
            .withFailMessage("bybit compose should expose BYBIT_API_KEY env var")
            .contains("BYBIT_API_KEY")
        assertThat(compose)
            .withFailMessage("bybit compose should default BYBIT_TESTNET to true")
            .contains("BYBIT_TESTNET:-true")
        val env = Files.readString(target.resolve(".env.example"))
        assertThat(env).contains("BYBIT_API_KEY=")
        assertThat(env).contains("BYBIT_TESTNET=true")
        val strat = Files.readString(target.resolve("strategies/ema_cross.qkt"))
        assertThat(strat).contains("BYBIT_LINEAR:BTCUSDT")
        val makefile = Files.readString(target.resolve("Makefile"))
        assertThat(makefile).contains("resync-dry-run")
        assertThat(makefile).contains("qkt resync /strategies/$(STRAT).qkt --as $(STRAT)")
        assertThat(makefile).contains("qkt reconcile $(STRAT)")
    }

    @Test
    fun `research kinds scaffold complete backtest projects`(
        @TempDir tmp: Path,
    ) {
        val expectedStrategies =
            mapOf(
                "backtest" to listOf("strategies/backtest.qkt"),
                "portfolio" to
                    listOf(
                        "strategies/portfolio.qkt",
                        "strategies/trend.qkt",
                        "strategies/mean_reversion.qkt",
                    ),
            )
        for ((kind, strategies) in expectedStrategies) {
            val target = tmp.resolve(kind)
            val (code, stdout, _) = invoke("create", "template", target.toString(), "--kind", kind)
            assertThat(code).isEqualTo(ExitCodes.SUCCESS)
            assertThat(stdout).contains("make backtest")
            assertThat(target.resolve(".gitignore")).exists()
            assertThat(target.resolve("README.md")).exists()
            assertThat(target.resolve("data/README.md")).exists()
            strategies.forEach { assertThat(target.resolve(it)).exists() }
            assertThat(Files.readString(target.resolve("qkt.config.yaml"))).contains("source: local")
            if (kind == "portfolio") {
                val portfolio = Files.readString(target.resolve("strategies/portfolio.qkt"))
                assertThat(Parser(Lexer(portfolio).tokenize()).parseFile())
                    .isInstanceOf(ParseResult.Success::class.java)
                val makefile = Files.readString(target.resolve("Makefile"))
                assertThat(makefile).contains("qkt resync /strategies/portfolio.qkt --as $(BOOK)")
                assertThat(makefile).contains("qkt reconcile $(BOOK)")
            }
        }
    }
}
