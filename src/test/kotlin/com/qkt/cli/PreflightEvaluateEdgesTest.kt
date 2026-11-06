package com.qkt.cli

import com.qkt.cli.daemon.StateDir
import java.nio.file.Files
import java.nio.file.Path
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PreflightEvaluateEdgesTest {
    @TempDir
    lateinit var tmp: Path

    private fun config(body: String = "account:\n  currency: USD\n"): Path =
        tmp.resolve("qkt.config.yaml").also { Files.writeString(it, body) }

    private fun file(
        name: String,
        body: String,
    ): Path = tmp.resolve(name).also { Files.writeString(it, body) }

    private fun evaluate(
        cfg: Path = config(),
        strategy: Path? = null,
        state: Path = tmp.resolve("state"),
    ) = ProductionPreflight.evaluate(cfg, StateDir.resolve(state.toString()), strategy)

    private fun List<PreflightCheck>.named(name: String) = single { it.name == name }

    @Test
    fun `a missing strategy file and a file that does not parse fail the parse check`() {
        assertThat(evaluate(strategy = tmp.resolve("gone.qkt")).named("strategy.parse").detail)
            .startsWith("file not found:")

        val broken = file("broken.qkt", "STRATEGY broken VERSION 1\nSYMBOLS\n  gold = BACKTEST:XAUUSD EVERY\n")
        val parse = evaluate(strategy = broken).named("strategy.parse")
        assertThat(parse.status).isEqualTo(PreflightStatus.FAIL)
        assertThat(parse.detail).containsPattern("^\\d+:\\d+ ")
    }

    @Test
    fun `a portfolio whose child cannot be loaded fails the compile check`() {
        val book = file("book.qkt", "PORTFOLIO book VERSION 1\nIMPORT 'missing.qkt' AS child\nRULES\n  RUN child\n")

        val compile = evaluate(strategy = book).named("strategy.compile")

        assertThat(compile.status).isEqualTo(PreflightStatus.FAIL)
        assertThat(compile.detail).isNotBlank()
    }

    @Test
    fun `an unwritable state root warns outside production instead of failing`() {
        val root = tmp.resolve("blocked")
        Files.createDirectories(root)
        Files.writeString(root.resolve("state"), "a file where the state directory belongs")

        val checks = evaluate(state = root)

        assertThat(checks.named("state.persistence").status).isEqualTo(PreflightStatus.WARN)
        assertThat(checks.named("state.persistence").detail).startsWith("not writable:")
        assertThat(checks.named("state.disk_headroom").let { it.status to it.detail.substringBefore(':') })
            .isEqualTo(PreflightStatus.WARN to "unreadable")
    }

    @Test
    fun `recent journal day files project the days until the state volume is full`() {
        val journals = tmp.resolve("state/state/audit-journal")
        Files.createDirectories(journals)
        Files.write(journals.resolve("audit-2026-11-01.jsonl"), ByteArray(100))
        Files.write(journals.resolve("audit-2026-11-02.jsonl.gz"), ByteArray(300))
        Files.write(journals.resolve("audit-2026-11-03.jsonl"), ByteArray(10))
        Files.write(journals.resolve("notes.txt"), ByteArray(10))

        val cfg = config("account:\n  currency: USD\nstate:\n  disk_free_alert_gb: 0\n")

        val headroom = evaluate(cfg).named("state.disk_headroom")

        assertThat(headroom.detail).contains("GB free; journals ~0 MB/day on disk -> ~").endsWith("day(s) to full")
    }

    @Test
    fun `an mt5 broker that cannot be resolved warns outside production`() {
        val cfg = config("account:\n  currency: USD\nbrokers:\n  custom:\n    type: mt5\n    magic: 7\n")

        val metadata = evaluate(cfg).named("broker.metadata")

        assertThat(metadata.status).isEqualTo(PreflightStatus.WARN)
        assertThat(metadata.detail).contains("gateway_url")
    }

    @Test
    fun `an account in another currency is a gateway problem`() {
        val gateway =
            MockWebServer().apply {
                dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest) =
                            MockResponse().setBody(
                                """{"balance":10000,"equity":10000,"currency":"EUR","leverage":100,"margin_mode":2,""" +
                                    """"login":1,"server":"Demo","trade_mode":0}""",
                            )
                    }
                start()
            }
        try {
            val url = gateway.url("/").toString().trimEnd('/')
            val cfg =
                config(
                    "account:\n  currency: USD\nbrokers:\n  exness:\n    type: mt5\n" +
                        "    gateway_url: $url\n    retry_attempts: 0\n",
                )

            val check = evaluate(cfg).named("broker.gateway.exness")

            assertThat(check.status).isEqualTo(PreflightStatus.WARN)
            assertThat(check.detail).contains("account currency expected USD, got EUR")
        } finally {
            gateway.shutdown()
        }
    }

    @Test
    fun `a complete telegram channel satisfies the alerts check`() {
        val telegram =
            config(
                "account:\n  currency: USD\nnotify:\n  telegram:\n    enabled: true\n" +
                    "    bot_token: t\n    chat_id: c\n",
            )
        assertThat(evaluate(telegram).named("notify.alerts").let { it.status to it.detail })
            .isEqualTo(PreflightStatus.PASS to "1 enabled channel(s)")
    }

    @Test
    fun `quote and volume reads are listed per alias in the data fields check`() {
        val strategy =
            file(
                "reads.qkt",
                """
                STRATEGY reads VERSION 1
                SYMBOLS
                    gold = BACKTEST:XAUUSD EVERY 1m
                    eur = BACKTEST:EURUSD EVERY 1m
                RULES
                    WHEN gold.bid > 0 AND eur.spread < 1 AND gold.volume > 0
                    THEN BUY gold SIZING 0.1
                """.trimIndent(),
            )

        assertThat(evaluate(strategy = strategy).named("data.fields").detail).isEqualTo("quote=eur,gold volume=gold")
    }
}
