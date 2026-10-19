package com.qkt.cli

import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

class CliOptionSchemaTest {
    @Test
    fun `bot accepts the session and agent flags`() {
        val calls =
            listOf(
                arrayOf(
                    "bot",
                    "buy",
                    "0.1",
                    "EXNESS:XAUUSD",
                    "--sl",
                    "trail:4@2",
                    "--tp",
                    "by:8",
                    "--exit-after",
                    "4h",
                ),
                arrayOf("bot", "submit", "CLOSE x", "--symbol", "EXNESS:XAUUSD", "--as", "a"),
                arrayOf("bot", "session", "finish", "--flatten", "--run", "r"),
                arrayOf(
                    "bot",
                    "session",
                    "start",
                    "--symbols",
                    "EXNESS:XAUUSD",
                    "--tf",
                    "1m",
                    "--reconcile",
                    "ignore-mismatches",
                ),
                arrayOf("bot", "session", "replay", "runs/r", "--out", "runs/r2", "--no-fetch"),
            )

        calls.forEach { argv -> assertThatCode { validate(Args(argv)) }.doesNotThrowAnyException() }
    }

    @Test
    fun `experiment schema preserves backtest pass-through options`() {
        val args =
            Args(
                arrayOf(
                    "experiment",
                    "run",
                    "--plan",
                    "plan.yaml",
                    "--data-root",
                    "data",
                    "--starting-balance",
                    "10000",
                    "--bars",
                ),
            )

        assertThatCode { validate(args) }.doesNotThrowAnyException()
    }

    @Test
    fun `promotion schema preserves paper validation metrics`() {
        val args =
            Args(
                arrayOf(
                    "promotion",
                    "paper",
                    "strategy",
                    "--paper-days",
                    "7",
                    "--avg-slippage-bps",
                    "1.5",
                    "--paper-status",
                    "passed",
                ),
            )

        assertThatCode { validate(args) }.doesNotThrowAnyException()
    }

    @Test
    fun `research verbs accept the venue position mode`() {
        for (verb in listOf("backtest", "sweep", "walkforward", "research")) {
            val args =
                Args(
                    arrayOf(
                        verb,
                        "strategy.qkt",
                        "--from",
                        "2026-01-01",
                        "--to",
                        "2026-01-02",
                        "--position-mode",
                        "netting",
                    ),
                )

            assertThatCode { validate(args) }.doesNotThrowAnyException()
        }
    }

    @Test
    fun `backtest schema accepts strict breaker and seeded chaos flags`() {
        val args =
            Args(
                arrayOf(
                    "backtest",
                    "strategy.qkt",
                    "--from",
                    "2026-01-01",
                    "--to",
                    "2026-01-02",
                    "--enforce-live-breakers",
                    "--chaos",
                    "--seed",
                    "42",
                ),
            )

        assertThatCode { validate(args) }.doesNotThrowAnyException()
    }

    @Test
    fun `golden schema accepts capture evidence paths`() {
        val args =
            Args(
                arrayOf(
                    "golden",
                    "capture",
                    "--session",
                    "alpha",
                    "--state-dir",
                    "state",
                    "--out",
                    "capture.zip",
                    "--read-only",
                ),
            )

        assertThatCode { validate(args) }.doesNotThrowAnyException()
    }

    @Test
    fun `golden schema accepts replay materialization paths`() {
        val args =
            Args(
                arrayOf(
                    "golden",
                    "materialize",
                    "--bundle",
                    "capture.zip",
                    "--out",
                    "replay-data",
                ),
            )

        assertThatCode { validate(args) }.doesNotThrowAnyException()
    }

    private fun validate(args: Args) {
        val schema = checkNotNull(CliOptionSchemas.forSubcommand(args.subcommand))
        args.validateOptions(
            valueOptions = schema.values,
            flags = schema.flags,
            optionalValueOptions = schema.optionalValues,
            shortAliases = schema.shortAliases,
        )
    }
}
