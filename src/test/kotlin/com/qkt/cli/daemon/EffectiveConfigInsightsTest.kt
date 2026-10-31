package com.qkt.cli.daemon

import com.qkt.cli.Config
import com.qkt.cli.EffectiveConfigFacts
import com.qkt.cli.daemon.portfolio.PortfolioDeployer
import com.qkt.common.TradingCalendar
import com.qkt.dsl.portfolio.PortfolioLoader
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.notify.NoopNotifier
import com.qkt.observe.insights.InsightsEventFamily
import com.qkt.observe.insights.InsightsSink
import com.qkt.observe.insights.SharedDealFetch
import com.qkt.persistence.NoopStatePersistor
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A secret anywhere in the config, written out or env-expanded, never reaches the collector. */
class EffectiveConfigInsightsTest {
    private class IdleSource : MarketSource {
        override val name = "Idle"
        override val capabilities = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String) = true

        override fun liveTicks(symbols: List<String>): TickFeed =
            object : TickFeed {
                private val gate = CountDownLatch(1)

                override fun next(): Tick? {
                    gate.await(30, TimeUnit.SECONDS)
                    return null
                }

                override fun close() = gate.countDown()
            }
    }

    private val bodies = StringBuffer()
    private val server =
        MockWebServer().also {
            it.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        bodies.append(request.body.readUtf8())
                        return MockResponse().setResponseCode(200).setBody("""{"accepted":1}""")
                    }
                }
            it.start(InetAddress.getByName("127.0.0.1"), 0)
        }
    private val sink =
        InsightsSink(
            url = "http://127.0.0.1:${server.port}/ingest",
            token = "sink-token",
            instanceId = "qkt-test",
            flushIntervalMs = 20L,
        )

    @AfterEach
    fun stop() {
        sink.close()
        server.shutdown()
        System.clearProperty(ENV_NAME)
    }

    private fun awaitBodies(until: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !until(bodies.toString())) Thread.sleep(20)
        return bodies.toString()
    }

    /** Every plausible home of a secret, each under its own marker and once as an env reference. */
    private fun plantedConfig(dir: Path): Config {
        System.setProperty(ENV_NAME, "$MARKER-env")
        val yaml =
            """
            source: "$MARKER-source"
            data_root: "/data/$MARKER-root"
            log_level: "$MARKER-log"
            starting_balance: "12000"
            account:
              currency: "USD"
              api_key: "$MARKER-accountkey"
              name: "${'$'}{$ENV_NAME}"
            runtime:
              mode: paper
              secret: "$MARKER-runtime"
              waivers: { alerts: { reason: "$MARKER-waiver" } }
            promotion: { signing_key: "$MARKER-promotion" }
            tv: { token: "$MARKER-tv", session: "${'$'}{$ENV_NAME}" }
            fetchers: { poly: { api_key: "$MARKER-fetcher" } }
            brokers:
              exness:
                type: mt5
                gateway_url: "https://user:$MARKER-urlpass@gw.example/${'$'}{$ENV_NAME}"
                gateway_token: "$MARKER-gwtoken"
                password: "$MARKER-password"
                login: "$MARKER-login"
                server: "$MARKER-server"
                api_key: "${'$'}{$ENV_NAME}"
                future_field: "$MARKER-future"
            notify:
              telegram:
                enabled: false
                type: telegram
                bot_token: "$MARKER-telegram"
                chat_id: "$MARKER-chat"
            insights: { enabled: true, events: [lifecycle], url: "https://$MARKER-insights", token: "$MARKER-itoken" }
            hub: { root: "/hub/$MARKER-hub" }
            execution: { preset: "$MARKER-exec" }
            report: { dir: "/r/$MARKER-report" }
            backtest: { name: "$MARKER-backtest" }
            risk: { max_daily_loss: "500" }
            """.trimIndent()
        return Config.load(Files.writeString(dir.resolve("qkt.config.yaml"), yaml))
    }

    @Test
    fun `a config with secrets planted everywhere sends none of them`(
        @TempDir dir: Path,
    ) {
        val cfg = plantedConfig(dir)
        val strategy =
            Files.writeString(
                dir.resolve("s.qkt"),
                "STRATEGY planted VERSION 1\n\nSYMBOLS\n    g = EXNESS:XAUUSD EVERY 1m\n\nRULES\n    WHEN g.close > 100\n    THEN BUY g SIZING 1\n",
            )
        val factory =
            com.qkt.cli.liveFactory(
                cfg = cfg,
                stateDir = StateDir.resolve(dir.resolve("state").toString()),
                marketSourceProvider = { IdleSource() },
                brokerFactories = emptyMap(),
                instrumentRegistry = null,
                calendarFor = { TradingCalendar.fxDefault() },
                persistor = NoopStatePersistor(),
                notifier = NoopNotifier,
                notifyEvents = emptySet(),
                insightsSink = sink,
                insightsDeployedIds = { listOf("planted") },
                insightsSharedDeals = SharedDealFetch(),
            )
        val handle = factory.create("planted", strategy, false)
        try {
            val all = awaitBodies { it.contains("\"effectiveConfig\"") }

            assertThat(all).contains(""""effectiveConfig":{"schema":1,"kind":"standalone"""")
            assertThat(all).containsPattern(""""effectiveConfigSha256":"[0-9a-f]{64}"""")
            assertThat(all).contains(""""accounts":[{"name":"EXNESS","type":"mt5"}]""")
            assertThat(all).contains(""""maxDailyLoss":{"value":500,"source":"configured"}""")
            assertThat(all).contains(""""tradingMode":"paper"""")
            assertThat(all).doesNotContain(MARKER)
        } finally {
            handle.close()
        }
    }

    @Test
    fun `each portfolio child states its book once per process and config`(
        @TempDir dir: Path,
    ) {
        val cfg =
            Config.load(
                Files.writeString(
                    dir.resolve("qkt.config.yaml"),
                    "risk: { max_daily_loss: \"900\" }\nbook_risk:\n  capital: \"80000\"\n  limits: { max_gross_exposure: \"2.5\" }\n",
                ),
            )
        val portfolio = Path.of("src/test/resources/dsl/portfolio_weighted.qkt")
        val deployer =
            PortfolioDeployer(
                stateDir = StateDir.resolve(dir.resolve("state").toString()),
                marketSourceProvider = { IdleSource() },
                maxDailyLoss = cfg.maxDailyLoss,
                bookRiskConfig = cfg.bookRisk,
                insightsSink = sink,
                insightsEvents = setOf(InsightsEventFamily.LIFECYCLE),
                effectiveConfig = EffectiveConfigFacts(cfg),
            )
        val first = deployer.deploy("weighted_book", PortfolioLoader.load(portfolio))
        awaitBodies { it.split("\"effectiveConfig\":").size > 2 }
        first.supervisor.stop()
        first.children.forEach { it.close() }
        val second = deployer.deploy("weighted_book", PortfolioLoader.load(portfolio))
        try {
            val all = awaitBodies { it.split("\"effectiveConfigOmitted\":true").size > 2 }

            assertThat(all.split("\"effectiveConfig\":").size - 1).isEqualTo(2)
            assertThat(all.split("\"effectiveConfigOmitted\":true").size - 1).isEqualTo(2)
            assertThat(all).contains(""""kind":"portfolio_child"""")
            assertThat(all).contains(""""name":"weighted_book","capital":{"value":80000,"source":"configured"}""")
            assertThat(all).contains(""""maxGrossExposure":{"value":2.5,"source":"configured"}""")
            assertThat(all).contains(""""appliesTo":"book"""")
        } finally {
            second.supervisor.stop()
            second.children.forEach { it.close() }
        }
    }

    private companion object {
        const val MARKER = "PLANTEDSECRET"
        const val ENV_NAME = "QKT_TEST_PLANTED_SECRET"
    }
}
