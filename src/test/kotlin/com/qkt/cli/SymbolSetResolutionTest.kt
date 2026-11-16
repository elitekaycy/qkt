package com.qkt.cli

import com.qkt.backtest.TickResolvedParityFixtures
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.sets.SymbolSetSpec
import com.qkt.instrument.YamlInstrumentRegistry
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SymbolSetResolutionTest {
    @TempDir
    lateinit var tmp: Path

    private val instruments =
        """
        instruments:
          - { qktSymbol: BACKTEST:XAUUSD, contractSize: 100, volumeStep: 0.01, volumeMin: 0.01, pointSize: 0.001, digits: 3, tradeStopsLevelPoints: 0, category: Metals }
          - { qktSymbol: BACKTEST:XAGUSD, contractSize: 5000, volumeStep: 0.01, volumeMin: 0.01, pointSize: 0.001, digits: 3, tradeStopsLevelPoints: 0, category: Metals }
          - { qktSymbol: BACKTEST:EURUSD, contractSize: 100000, volumeStep: 0.01, volumeMin: 0.01, pointSize: 0.00001, digits: 5, tradeStopsLevelPoints: 0, category: Forex }
          - { qktSymbol: BACKTEST:AAPL, contractSize: 1, volumeStep: 1, volumeMin: 1, pointSize: 0.01, digits: 2, tradeStopsLevelPoints: 0, category: Stocks, equityCosts: {} }
        """.trimIndent()

    private fun strategy(set: String) =
        """
        STRATEGY sets VERSION 1
        SYMBOLS
            m = $set EVERY 15m
        RULES
            FOR EACH s IN m DO
              WHEN ema(s.close, 3) CROSSES ABOVE ema(s.close, 9) THEN BUY s SIZING 0.1 BRACKET { STOP LOSS PCT 0.3, TAKE PROFIT RR 2 }
        """.trimIndent()

    private fun parse(src: String): StrategyAst = (Dsl.parse(src) as ParseResult.Success).value

    private fun catalog() =
        YamlInstrumentRegistry
            .load(
                tmp.resolve("instruments.yaml").also {
                    Files.writeString(it, instruments)
                },
            ).all()

    private fun spec(text: String) = SymbolSetSpec.parse(text) as SymbolSetSpec.Filtered

    @Test
    fun `resolve replaces a filtered set by its members and notes them, leaving a set-free strategy alone`() {
        val notes = mutableListOf<String>()
        val ast = parse(strategy("BACKTEST:* WHERE category = Metals TOP 5 BY contract_size ASC"))
        val resolved = SymbolSetResolution.resolve(ast, catalog(), 0L, survivorshipAccepted = true, note = notes::add)
        assertThat(resolved.streams.map { it.qktSymbol }).containsExactly("BACKTEST:XAUUSD", "BACKTEST:XAGUSD")
        assertThat(
            resolved.rules.map { ((it as WhenThen).action as Buy).stream },
        ).containsExactly("m.XAUUSD", "m.XAGUSD")
        assertThat(notes).containsExactly("symbol set 'm': 2 member(s) resolved at start: XAUUSD, XAGUSD")
        val plain = parse(strategy("BACKTEST:{XAUUSD}"))
        assertThat(SymbolSetResolution.resolve(plain, emptyList(), 0L, survivorshipAccepted = false)).isSameAs(plain)
    }

    @Test
    fun `members refuses what the catalog cannot answer, naming the set`() {
        fun refuse(
            text: String,
            accepted: Boolean = true,
        ) = assertThatThrownBy { SymbolSetResolution.members("m", spec(text), catalog(), 0L, accepted) }
            .isInstanceOf(BacktestContext.Companion.SetupError::class.java)
        refuse(
            "BACKTEST:* WHERE kind = spot TOP 501 BY symbol",
        ).hasMessage("symbol set 'm': TOP 501 is above the member cap of 500")
        refuse(
            "EXNESS:* WHERE kind = spot TOP 5 BY symbol",
        ).hasMessage("symbol set 'm': the instruments file lists no EXNESS: symbol")
        refuse("BACKTEST:* WHERE category = Metals TOP 5 BY symbol", accepted = false)
            .hasMessageContaining(
                "symbol set 'm' filters BACKTEST equities from an instruments file with no listing history",
            ).hasMessageContaining("--survivorship-accepted")
        refuse("BACKTEST:* WHERE category = Crypto TOP 5 BY symbol")
            .hasMessage("symbol set 'm': no BACKTEST instrument passes category = \"Crypto\"")
        assertThat(
            SymbolSetResolution.members(
                "m",
                spec("backtest:* WHERE kind = equity TOP 1 BY symbol"),
                catalog(),
                0L,
                true,
            ),
        ).containsExactly("AAPL")
        val fxOnly = catalog().filter { it.equityCosts == null }
        assertThat(
            SymbolSetResolution.members(
                "m",
                spec("BACKTEST:* WHERE category = Forex TOP 1 BY symbol"),
                fxOnly,
                0L,
                false,
            ),
        ).containsExactly("EURUSD")
    }

    @Test
    fun `the catalog is the instruments flag or the data root's file, and must exist`() {
        val root = tmp.resolve("root").also { Files.createDirectories(it) }
        assertThatThrownBy { SymbolSetResolution.catalog(Args(arrayOf("backtest")), root) }
            .isInstanceOf(BacktestContext.Companion.SetupError::class.java)
            .hasMessageContaining("no instruments file at ${root.resolve("instruments.yaml")}")
        Files.writeString(root.resolve("instruments.yaml"), instruments)
        assertThat(SymbolSetResolution.catalog(Args(arrayOf("backtest")), root)).hasSize(4)
        val explicit =
            tmp
                .resolve(
                    "other.yaml",
                ).also { Files.writeString(it, instruments.lines().take(2).joinToString("\n")) }
        assertThat(
            SymbolSetResolution.catalog(Args(arrayOf("backtest", "--instruments", explicit.toString())), root),
        ).hasSize(1)
    }

    @Test
    fun `a backtest resolves its filtered set at start, refuses equities without the flag and needs --from`() {
        val dataRoot = tmp.resolve("data")
        TickResolvedParityFixtures.seedTicks(dataRoot, days = 3)
        Files.writeString(dataRoot.resolve("instruments.yaml"), instruments)
        val strategy =
            tmp.resolve("sets.qkt").also {
                Files.writeString(it, strategy("BACKTEST:* WHERE symbol = XAUUSD TOP 1 BY symbol"))
            }
        val base =
            arrayOf(
                "backtest",
                strategy.toString(),
                "--data-root",
                dataRoot.toString(),
                "--no-fetch",
                "--allow-incomplete",
                "--json",
            )
        val window = arrayOf("--from", "2024-01-02", "--to", "2024-01-05")

        val refused = run(base + window)
        assertThat(refused.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(refused.err).contains("symbol set 'm' filters BACKTEST equities").contains("--survivorship-accepted")

        val noFrom = run(base + arrayOf("--survivorship-accepted"))
        assertThat(noFrom.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(noFrom.err).contains("a filtered symbol set is resolved as of --from, which is missing")

        val reportDir = tmp.resolve("report")
        val ok = run(base + window + arrayOf("--survivorship-accepted", "--report-dir", reportDir.toString()))
        assertThat(ok.code).describedAs(ok.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(ok.err).contains("qkt: note: symbol set 'm': 1 member(s) resolved at start: XAUUSD")
        assertThat(Files.readString(reportDir.resolve("trades.csv")).lines().size).isGreaterThan(2)
    }

    private class Outcome(
        val code: Int,
        val err: String,
    )

    private fun run(argv: Array<String>): Outcome {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val (origOut, origErr) = System.out to System.err
        val code =
            try {
                System.setOut(PrintStream(out))
                System.setErr(PrintStream(err))
                BacktestCommand(Args(argv)).run()
            } finally {
                System.setOut(origOut)
                System.setErr(origErr)
            }
        return Outcome(code, err.toString() + out)
    }
}
