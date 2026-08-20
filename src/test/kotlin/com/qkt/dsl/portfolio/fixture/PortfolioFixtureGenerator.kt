package com.qkt.dsl.portfolio.fixture

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Deterministic generator for portfolio + strategy DSL fixtures.
 *
 * The generated strategies use conditions that resolve as early as possible so the test matrix
 * can focus on portfolio wiring (allocation, gating, risk sharing, sizing propagation) rather
 * than signal quality. Every variant is self-contained: it can be written to a temporary
 * directory and fed to [com.qkt.dsl.portfolio.PortfolioLoader] or `qkt backtest`.
 */
object PortfolioFixtureGenerator {
    /** All named variants. Names are stable so test failures are reproducible. */
    fun all(): List<PortfolioVariant> =
        listOf(
            weightedTwoChildren(),
            weightedThreeChildrenWithCash(),
            alwaysRunNoCapital(),
            whenConditionalToggle(),
            holdChildSurvivesDeactivation(),
            paramOverrideFromPortfolio(),
            riskOfBookRequiresCapital(),
            regimeWeightedTwoStates(),
            regimeWeightedWithHold(),
            multiStreamChild(),
            bookRiskExposureLimit(),
            bookRiskDeRiskLadder(),
            perStrategyRiskCap(),
        )

    /** Find a variant by stable name. */
    fun byName(name: String): PortfolioVariant =
        all().firstOrNull { it.name == name }
            ?: error("unknown portfolio variant '$name'")

    private fun weightedTwoChildren(): PortfolioVariant {
        val capital = BigDecimal("10000")
        val strategies =
            mapOf(
                "trend.qkt" to simpleStrategy("trend", "t", "BACKTEST:XAUUSD", "15m", "0.1"),
                "meanrev.qkt" to simpleStrategy("meanrev", "m", "BACKTEST:XAUUSD", "15m", "0.1"),
            )
        val portfolio =
            """
            PORTFOLIO weighted_two VERSION 1 CAPITAL $capital
            IMPORT 'trend.qkt' AS trend
            IMPORT 'meanrev.qkt' AS meanrev
            RULES
              RUN trend WEIGHT 0.6
              RUN meanrev WEIGHT 0.4
            """.trimIndent()
        val config = baseConfig()
        return PortfolioVariant(
            name = "weighted_two",
            strategies = strategies,
            portfolio = portfolio,
            config = config,
            expected =
                ExpectedInvariants(
                    capital = capital,
                    allocationByAlias = mapOf("trend" to bd("6000"), "meanrev" to bd("4000")),
                    alwaysRunAliases = setOf("trend", "meanrev"),
                    expectedMinTrades = 2,
                ),
        )
    }

    private fun weightedThreeChildrenWithCash(): PortfolioVariant {
        val capital = BigDecimal("10000")
        val strategies =
            mapOf(
                "a.qkt" to simpleStrategy("a", "x", "BACKTEST:XAUUSD", "15m", "0.05"),
                "b.qkt" to simpleStrategy("b", "x", "BACKTEST:XAUUSD", "15m", "0.05"),
                "c.qkt" to simpleStrategy("c", "x", "BACKTEST:XAUUSD", "15m", "0.05"),
            )
        val portfolio =
            """
            PORTFOLIO weighted_three VERSION 1 CAPITAL $capital
            IMPORT 'a.qkt' AS a
            IMPORT 'b.qkt' AS b
            IMPORT 'c.qkt' AS c
            RULES
              RUN a WEIGHT 0.3
              RUN b WEIGHT 0.2
              RUN c WEIGHT 0.4
            """.trimIndent()
        return PortfolioVariant(
            name = "weighted_three",
            strategies = strategies,
            portfolio = portfolio,
            config = baseConfig(),
            expected =
                ExpectedInvariants(
                    capital = capital,
                    allocationByAlias = mapOf("a" to bd("3000"), "b" to bd("2000"), "c" to bd("4000")),
                    alwaysRunAliases = setOf("a", "b", "c"),
                    expectedMinTrades = 2,
                ),
        )
    }

    private fun alwaysRunNoCapital(): PortfolioVariant {
        val strategies = mapOf("child.qkt" to simpleStrategy("child", "x", "BACKTEST:XAUUSD", "15m", "0.1"))
        val portfolio =
            """
            PORTFOLIO always_run VERSION 1
            IMPORT 'child.qkt' AS child
            RULES
              RUN child
            """.trimIndent()
        return PortfolioVariant(
            name = "always_run",
            strategies = strategies,
            portfolio = portfolio,
            config = baseConfig(),
            expected =
                ExpectedInvariants(
                    alwaysRunAliases = setOf("child"),
                    expectedMinTrades = 1,
                ),
        )
    }

    private fun whenConditionalToggle(): PortfolioVariant {
        val strategies = mapOf("child.qkt" to simpleStrategy("child", "x", "BACKTEST:XAUUSD", "15m", "0.1"))
        val portfolio =
            """
            PORTFOLIO when_toggle VERSION 1
            SYMBOLS
              x = BACKTEST:XAUUSD EVERY 15m
            IMPORT 'child.qkt' AS child
            RULES
              WHEN x.close > 0 RUN child
            """.trimIndent()
        return PortfolioVariant(
            name = "when_toggle",
            strategies = strategies,
            portfolio = portfolio,
            config = baseConfig(),
            expected =
                ExpectedInvariants(
                    conditionalAliases = setOf("child"),
                    gateToggleBarCount = 1,
                    expectedMinTrades = 1,
                ),
        )
    }

    private fun holdChildSurvivesDeactivation(): PortfolioVariant {
        val strategies = mapOf("child.qkt" to simpleStrategy("child", "x", "BACKTEST:XAUUSD", "15m", "0.1"))
        val portfolio =
            """
            PORTFOLIO hold_child VERSION 1 CAPITAL 10000
            SYMBOLS
              x = BACKTEST:XAUUSD EVERY 15m
            IMPORT 'child.qkt' AS child HOLD
            RULES
              WHEN x.close > 0 RUN child WEIGHT 1.0
            """.trimIndent()
        return PortfolioVariant(
            name = "hold_child",
            strategies = strategies,
            portfolio = portfolio,
            config = baseConfig(),
            expected =
                ExpectedInvariants(
                    capital = bd("10000"),
                    allocationByAlias = mapOf("child" to bd("10000")),
                    conditionalAliases = setOf("child"),
                    holdAliases = setOf("child"),
                    expectedMinTrades = 1,
                ),
        )
    }

    private fun paramOverrideFromPortfolio(): PortfolioVariant {
        val strategies =
            mapOf(
                "child.qkt" to
                    """
                    STRATEGY child VERSION 1
                    SYMBOLS
                      x = BACKTEST:XAUUSD EVERY 15m
                    PARAM qty = 0.01
                    RULES
                      WHEN x.close > 0 THEN BUY x SIZING qty
                    """.trimIndent(),
            )
        val portfolio =
            """
            PORTFOLIO param_override VERSION 1 CAPITAL 10000
            IMPORT 'child.qkt' AS child
            RULES
              RUN child WEIGHT 1.0 OVERRIDE { qty = 0.05 }
            """.trimIndent()
        return PortfolioVariant(
            name = "param_override",
            strategies = strategies,
            portfolio = portfolio,
            config = baseConfig(),
            expected =
                ExpectedInvariants(
                    capital = bd("10000"),
                    allocationByAlias = mapOf("child" to bd("10000")),
                    alwaysRunAliases = setOf("child"),
                    expectedMinTrades = 1,
                ),
        )
    }

    private fun riskOfBookRequiresCapital(): PortfolioVariant {
        val strategies =
            mapOf(
                "child.qkt" to
                    """
                    STRATEGY child VERSION 1
                    SYMBOLS
                      x = BACKTEST:XAUUSD EVERY 15m
                    RULES
                      WHEN x.close > 0 AND POSITION.x = 0
                      THEN BUY x SIZING 1.0 PCT RISK OF BOOK BRACKET { STOP LOSS PCT 10, TAKE PROFIT AT 1000000 }
                    """.trimIndent(),
            )
        val portfolio =
            """
            PORTFOLIO risk_of_book VERSION 1 CAPITAL 50000
            IMPORT 'child.qkt' AS child
            RULES
              RUN child WEIGHT 1.0
            """.trimIndent()
        return PortfolioVariant(
            name = "risk_of_book",
            strategies = strategies,
            portfolio = portfolio,
            config = baseConfig(),
            expected =
                ExpectedInvariants(
                    capital = bd("50000"),
                    allocationByAlias = mapOf("child" to bd("50000")),
                    alwaysRunAliases = setOf("child"),
                    expectedMinTrades = 1,
                ),
        )
    }

    private fun regimeWeightedTwoStates(): PortfolioVariant {
        val strategies =
            mapOf(
                "trend.qkt" to simpleStrategy("trend", "g", "BACKTEST:XAUUSD", "15m", "0.1"),
                "meanrev.qkt" to simpleStrategy("meanrev", "g", "BACKTEST:XAUUSD", "15m", "0.1"),
            )
        val portfolio =
            """
            PORTFOLIO regime_two VERSION 1 CAPITAL 10000
            SYMBOLS
              g = BACKTEST:XAUUSD EVERY 15m
            IMPORT 'trend.qkt' AS trend
            IMPORT 'meanrev.qkt' AS meanrev
            REGIMES
              NAME r
              STATE up WHEN g.close > g.open
              STATE down DEFAULT
            ALLOCATE
              METHOD regime_weighted
              up -> trend 0.8, meanrev 0.2
              down -> trend 0.2, meanrev 0.8
            RULES
              RUN trend
              RUN meanrev
            """.trimIndent()
        return PortfolioVariant(
            name = "regime_two",
            strategies = strategies,
            portfolio = portfolio,
            config = baseConfig(bookRiskRegime = true),
            expected =
                ExpectedInvariants(
                    capital = bd("10000"),
                    regimeWeightsByState =
                        mapOf(
                            "up" to mapOf("trend" to bd("0.8"), "meanrev" to bd("0.2")),
                            "down" to mapOf("trend" to bd("0.2"), "meanrev" to bd("0.8")),
                        ),
                    alwaysRunAliases = setOf("trend", "meanrev"),
                    expectedMinTrades = 2,
                ),
        )
    }

    private fun regimeWeightedWithHold(): PortfolioVariant {
        val strategies =
            mapOf(
                "trend.qkt" to simpleStrategy("trend", "g", "BACKTEST:XAUUSD", "15m", "0.1"),
                "meanrev.qkt" to simpleStrategy("meanrev", "g", "BACKTEST:XAUUSD", "15m", "0.1"),
            )
        val portfolio =
            """
            PORTFOLIO regime_hold VERSION 1 CAPITAL 10000
            SYMBOLS
              g = BACKTEST:XAUUSD EVERY 15m
            IMPORT 'trend.qkt' AS trend HOLD
            IMPORT 'meanrev.qkt' AS meanrev
            REGIMES
              NAME r
              STATE up WHEN g.close > g.open
              STATE down DEFAULT
            ALLOCATE
              METHOD regime_weighted
              up -> trend 0.8, meanrev 0.2
              down -> trend 0.0, meanrev 1.0
            RULES
              RUN trend
              RUN meanrev
            """.trimIndent()
        return PortfolioVariant(
            name = "regime_hold",
            strategies = strategies,
            portfolio = portfolio,
            config = baseConfig(bookRiskRegime = true),
            expected =
                ExpectedInvariants(
                    capital = bd("10000"),
                    regimeWeightsByState =
                        mapOf(
                            "up" to mapOf("trend" to bd("0.8"), "meanrev" to bd("0.2")),
                            "down" to mapOf("trend" to bd("0.0"), "meanrev" to bd("1.0")),
                        ),
                    alwaysRunAliases = setOf("trend", "meanrev"),
                    holdAliases = setOf("trend"),
                    expectedMinTrades = 2,
                ),
        )
    }

    private fun multiStreamChild(): PortfolioVariant {
        val strategies =
            mapOf(
                "dual.qkt" to
                    """
                    STRATEGY dual VERSION 1
                    SYMBOLS
                      gold = BACKTEST:XAUUSD EVERY 15m,
                      eur = BACKTEST:EURUSD EVERY 15m
                    RULES
                      WHEN gold.close > 0 AND eur.close > 0 AND POSITION.gold = 0
                      THEN BUY gold SIZING 0.1 BRACKET { STOP LOSS PCT 1, TAKE PROFIT RR 2 }
                    """.trimIndent(),
            )
        val portfolio =
            """
            PORTFOLIO multi_stream VERSION 1
            IMPORT 'dual.qkt' AS dual
            RULES
              RUN dual
            """.trimIndent()
        return PortfolioVariant(
            name = "multi_stream",
            strategies = strategies,
            portfolio = portfolio,
            config = baseConfig(),
            expected =
                ExpectedInvariants(
                    alwaysRunAliases = setOf("dual"),
                    expectedMinTrades = 1,
                ),
        )
    }

    private fun bookRiskExposureLimit(): PortfolioVariant {
        val strategies =
            mapOf(
                "child.qkt" to
                    """
                    STRATEGY child VERSION 1
                    SYMBOLS
                      x = BACKTEST:XAUUSD EVERY 15m
                    RULES
                      WHEN x.close > 0 THEN BUY x SIZING 1.0
                    """.trimIndent(),
            )
        val portfolio =
            """
            PORTFOLIO book_exposure VERSION 1 CAPITAL 1000
            SYMBOLS
              x = BACKTEST:XAUUSD EVERY 15m
            IMPORT 'child.qkt' AS child
            RULES
              WHEN x.close > 0 RUN child WEIGHT 1.0
            """.trimIndent()
        val config =
            baseConfig() + "\n" +
                """
                book_risk:
                  capital: "1000"
                  limits:
                    max_gross_exposure: "0.15"
                """.trimIndent()
        return PortfolioVariant(
            name = "book_exposure",
            strategies = strategies,
            portfolio = portfolio,
            config = config,
            expected =
                ExpectedInvariants(
                    capital = bd("1000"),
                    allocationByAlias = mapOf("child" to bd("1000")),
                    conditionalAliases = setOf("child"),
                    expectsBookRisk = true,
                    expectedRiskRejections = true,
                ),
        )
    }

    private fun bookRiskDeRiskLadder(): PortfolioVariant {
        val strategies =
            mapOf(
                "child.qkt" to
                    """
                    STRATEGY child VERSION 1
                    SYMBOLS
                      x = BACKTEST:XAUUSD EVERY 15m
                    RULES
                      WHEN x.close > 0 AND POSITION.x = 0 THEN BUY x SIZING 1.0
                      WHEN x.close < 0 AND POSITION.x > 0 THEN SELL x SIZING POSITION.x
                    """.trimIndent(),
            )
        val portfolio =
            """
            PORTFOLIO book_derisk VERSION 1 CAPITAL 10000
            SYMBOLS
              x = BACKTEST:XAUUSD EVERY 15m
            IMPORT 'child.qkt' AS child
            RULES
              RUN child WEIGHT 1.0
            """.trimIndent()
        val config =
            baseConfig() + "\n" +
                """
                book_risk:
                  capital: "10000"
                  de_risk:
                    ladder:
                      - drawdown: "0.005"
                        factor: "0.5"
                """.trimIndent()
        return PortfolioVariant(
            name = "book_derisk",
            strategies = strategies,
            portfolio = portfolio,
            config = config,
            expected =
                ExpectedInvariants(
                    capital = bd("10000"),
                    allocationByAlias = mapOf("child" to bd("10000")),
                    alwaysRunAliases = setOf("child"),
                    expectsBookRisk = true,
                    expectedMinTrades = 1,
                ),
        )
    }

    private fun perStrategyRiskCap(): PortfolioVariant {
        val strategies = mapOf("child.qkt" to simpleStrategy("child", "x", "BACKTEST:XAUUSD", "15m", "1.0"))
        val portfolio =
            """
            PORTFOLIO per_strat_risk VERSION 1 CAPITAL 10000
            IMPORT 'child.qkt' AS child
            RULES
              RUN child WEIGHT 1.0
            """.trimIndent()
        val config =
            baseConfig() + "\n" +
                """
                risk:
                  per_strategy:
                    per_strat_risk:child:
                      max_position_size: "0.5"
                """.trimIndent()
        return PortfolioVariant(
            name = "per_strat_risk",
            strategies = strategies,
            portfolio = portfolio,
            config = config,
            expected =
                ExpectedInvariants(
                    capital = bd("10000"),
                    allocationByAlias = mapOf("child" to bd("10000")),
                    alwaysRunAliases = setOf("child"),
                    expectedRiskRejections = true,
                ),
        )
    }

    /** Minimal base config with a BACKTEST broker block so the CLI parses cleanly. */
    private fun baseConfig(bookRiskRegime: Boolean = false): String {
        val lines = mutableListOf<String>()
        lines.add("source: backtest")
        lines.add("data_root: ./data")
        lines.add("starting_balance: 10000")
        lines.add("log_level: info")
        lines.add("brokers:")
        lines.add("  backtest:")
        lines.add("    type: paper")
        if (bookRiskRegime) {
            lines.add("book_risk:")
            lines.add("  capital: \"10000\"")
            lines.add("  allocation:")
            lines.add("    method: \"REGIME_WEIGHTED\"")
            lines.add("    rebalance_every_bars: 1")
        }
        return lines.joinToString("\n")
    }

    private fun simpleStrategy(
        name: String,
        alias: String,
        symbol: String,
        timeframe: String,
        qty: String,
    ): String =
        """
        STRATEGY $name VERSION 1
        SYMBOLS
          $alias = $symbol EVERY $timeframe
        RULES
          WHEN $alias.close > 0 THEN BUY $alias SIZING $qty BRACKET { STOP LOSS PCT 1, TAKE PROFIT RR 2 }
        """.trimIndent()

    private fun bd(s: String): BigDecimal = BigDecimal(s).setScale(8, RoundingMode.HALF_UP)
}
