package com.qkt.observe.insights

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The session's half of the effective config: what only a running session knows, then the hash. */
class EffectiveConfigResolutionTest {
    private val config =
        linkedMapOf(
            "schema" to 1,
            "accounts" to listOf(linkedMapOf("name" to "EXNESS", "type" to "mt5")),
        )
    private val metadata =
        mapOf(
            "runtimeMode" to "live",
            "symbols" to listOf("EXNESS:XAUUSD", "EXNESS:EURUSD"),
            "effectiveConfig" to config,
        )

    @Test
    fun `no config states nothing`() {
        val bare = mapOf("runtimeMode" to "live")

        assertThat(InsightsVenues().withResolvedEffectiveConfig(bare) { 0L }).isEqualTo(bare)
    }

    @Test
    fun `the feed's poll interval is the account's, and a streamed feed states none`() {
        val polled =
            InsightsVenues().withResolvedEffectiveConfig(
                metadata,
            ) { if (it.endsWith("XAUUSD")) 500L else 250L }
        val streamed = InsightsVenues().withResolvedEffectiveConfig(metadata) { 0L }

        val account = ((polled["effectiveConfig"] as Map<*, *>)["accounts"] as List<*>).single() as Map<*, *>
        assertThat(account["tickPollIntervalMs"]).isEqualTo(500L)
        val quiet = ((streamed["effectiveConfig"] as Map<*, *>)["accounts"] as List<*>).single() as Map<*, *>
        assertThat(quiet.keys).doesNotContain("tickPollIntervalMs")
    }

    @Test
    fun `the hash is stable for one config and moves with a value`() {
        fun sha(lag: Long) =
            InsightsVenues().withResolvedEffectiveConfig(metadata) { lag }["effectiveConfigSha256"] as String

        assertThat(sha(500L)).hasSize(64).isEqualTo(sha(500L)).isNotEqualTo(sha(250L))
    }

    @Test
    fun `a session with no venue behind its accounts in a paper deploy trades paper`() {
        val paper = metadata + ("runtimeMode" to "paper")
        val out = InsightsVenues().withResolvedEffectiveConfig(paper) { 0L }

        assertThat((out["effectiveConfig"] as Map<*, *>)["tradingMode"]).isEqualTo("paper")
    }

    @Test
    fun `a failure resolving telemetry leaves the start without a config, and never throws`() {
        val out = InsightsVenues().withResolvedEffectiveConfig(metadata) { error("feed not routed") }

        assertThat(out).doesNotContainKeys("effectiveConfig", "effectiveConfigSha256")
        assertThat(out).containsEntry("runtimeMode", "live")
    }

    @Test
    fun `an account states what its venue says, a demo account trades demo, and any real account is live`() {
        fun venues(tradeMode: String) =
            InsightsVenues(brokers = {
                listOf(
                    java.lang.reflect.Proxy.newProxyInstance(
                        javaClass.classLoader,
                        arrayOf(com.qkt.broker.Broker::class.java, com.qkt.broker.VenueDescribed::class.java),
                    ) { _, method, _ ->
                        when (method.name) {
                            "venueIdentity" ->
                                com.qkt.broker.VenueIdentity(
                                    "mt5",
                                    "exness",
                                    "EXNESS",
                                    "mt5:0123456789abcdef",
                                    tradeMode,
                                )
                            else -> null
                        }
                    } as com.qkt.broker.Broker,
                )
            })

        val demo = venues("demo").withResolvedEffectiveConfig(metadata) { 0L }["effectiveConfig"] as Map<*, *>
        val account = (demo["accounts"] as List<*>).single() as Map<*, *>
        assertThat(account["connector"]).isEqualTo("mt5")
        assertThat(account["adapter"]).isEqualTo("exness")
        assertThat(account["environment"]).isEqualTo("demo")
        assertThat(account.toString()).doesNotContain("0123456789abcdef")
        assertThat(demo["tradingMode"]).isEqualTo("demo")
        val real = venues("real").withResolvedEffectiveConfig(metadata) { 0L }["effectiveConfig"] as Map<*, *>
        assertThat(real["tradingMode"]).isEqualTo("live")
    }
}
