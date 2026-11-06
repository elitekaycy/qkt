package com.qkt.observe.insights

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.broker.VenueDescribed
import com.qkt.broker.VenueIdentity
import com.qkt.execution.OrderRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The trading mode a start states is the riskiest environment its venues report. */
class EffectiveConfigTradingModeTest {
    private class Venue(
        private val identity: VenueIdentity,
    ) : Broker,
        VenueDescribed {
        override val name = identity.label
        override val capabilities = emptySet<OrderTypeCapability>()

        override fun submit(request: OrderRequest) = SubmitAck(request.id, null, accepted = true)

        override fun cancel(orderId: String) {}

        override fun venueIdentity() = identity
    }

    private fun resolve(
        vararg identities: VenueIdentity,
        runtimeMode: String = "live",
    ): Map<*, *> {
        val venues = InsightsVenues({ identities.map(::Venue) })
        val accounts = identities.map { linkedMapOf("name" to it.label) }
        val metadata =
            mapOf(
                "runtimeMode" to runtimeMode,
                "symbols" to identities.map { "${it.label}:X" },
                "effectiveConfig" to mapOf("accounts" to accounts),
            )
        return venues.withResolvedEffectiveConfig(metadata) { 0L }["effectiveConfig"] as Map<*, *>
    }

    private fun id(
        label: String,
        mode: String?,
        connector: String = "mt5",
        adapter: String = "exness",
    ) = VenueIdentity(connector, adapter, label, "acct", mode)

    @Test
    fun `the riskiest environment names the mode`() {
        assertThat(resolve(id("A", "demo"), id("B", "real"))["tradingMode"]).isEqualTo("live")
        assertThat(resolve(id("A", "demo"), id("B", "contest"))["tradingMode"]).isEqualTo("contest")
        assertThat(resolve(id("A", "demo"), id("B", "demo"))["tradingMode"]).isEqualTo("demo")
        assertThat(resolve(id("A", null, connector = "paper"))["tradingMode"]).isEqualTo("paper")
        assertThat(resolve(id("A", null))["tradingMode"]).isNull()
        assertThat(resolve()["tradingMode"]).isEqualTo("paper")
    }

    @Test
    fun `a venue's identity is stated only when it is one qkt knows`() {
        val account = (resolve(id("A", "real"))["accounts"] as List<*>).single() as Map<*, *>
        assertThat(account["connector"]).isEqualTo("mt5")
        assertThat(account["adapter"]).isEqualTo("exness")
        assertThat(account["environment"]).isEqualTo("real")

        val odd =
            (
                resolve(
                    id("B", "staging", connector = "fix", adapter = "Bad Adapter!"),
                )["accounts"] as List<*>
            ).single() as Map<*, *>
        assertThat(odd.keys).doesNotContain("connector", "adapter", "environment")
    }

    @Test
    fun `symbols and accounts that are not lists are read as none`() {
        val metadata = mapOf("symbols" to "X", "effectiveConfig" to mapOf("accounts" to "A"))

        val config = InsightsVenues().withResolvedEffectiveConfig(metadata) { 0L }["effectiveConfig"] as Map<*, *>

        assertThat(config["accounts"]).isEqualTo(emptyList<Any>())
        assertThat(config["tradingMode"]).isEqualTo("paper")
    }
}
