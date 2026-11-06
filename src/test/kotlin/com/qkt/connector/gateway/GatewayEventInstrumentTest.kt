package com.qkt.connector.gateway

import com.qkt.common.FixedClock
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ProductType
import com.qkt.instrument.EventTerms
import com.qkt.instrument.ProbabilityFee
import com.qkt.instrument.StrikePredicate
import com.qkt.instrument.StrikeType
import com.qkt.instrument.TradeMode
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * An event contract a VGP gateway lists (`kind: event`) reaches qkt as one [com.qkt.instrument.InstrumentMeta] with its
 * [EventTerms], found by its symbol (the code with `-` written `_`) like any other listed instrument.
 */
class GatewayEventInstrumentTest {
    private val ladderCode = "KXINXU-26NOV06H1500-T7425"
    private val fake = FakeGateway(listOf(ladderCode, "KXNBAGAME-26NOV06DETBOS-DET", "AAPL"))
    private val context = ConnectorContext(null, mapOf("GW_KEY" to "secret"), FixedClock(5L))

    @AfterEach
    fun stop() = fake.shutdown()

    private fun account() =
        GatewayConnector()
            .open(
                listOf(
                    AccountConfig(
                        "kalshi",
                        "gateway",
                        mapOf(
                            "type" to "gateway",
                            "gateway_url" to fake.url,
                            "api_key" to "env:GW_KEY",
                            "expected_adapter" to "fake",
                            "expected_account_login" to "7",
                            "expected_trade_mode" to "demo",
                        ),
                    ),
                ),
                context,
            ).single()

    /** The wire spec's own example, as the gateway sends it. */
    private val ladderJson =
        """
        {"code": "KXINXU-26NOV06H1500-T7425", "kind": "event", "currency": "USD", "contract_size": "1",
         "tick_size": "0.01", "volume_step": "0.01", "volume_min": "1", "expiry": 1794006000000,
         "series": "KXINXU", "event": "KXINXU-26NOV06H1500", "strike_type": "greater_or_equal", "floor": "7425",
         "open_at": 1794002400000, "close_at": 1794006000000, "occasion": "26NOV06H1500",
         "can_close_early": false, "exclusive": false, "frequency": "1h", "title": "7,425 or above",
         "fee_model": "probability", "taker_fee_rate": "0.07", "maker_fee_rate": "0.0175", "fee_rounding": "0.01"}
        """.trimIndent()

    private val ladder = Json { ignoreUnknownKeys = true }.decodeFromString(WireInstrument.serializer(), ladderJson)

    private val headToHead =
        WireInstrument(
            "KXNBAGAME-26NOV06DETBOS-DET",
            "event",
            "USD",
            "1",
            "0.01",
            "1",
            "1",
            expiry = 1_794_020_000_000L,
            trading = "disabled",
            series = "KXNBAGAME",
            event = "KXNBAGAME-26NOV06DETBOS",
            strikeType = "custom",
            openAt = 1_793_900_000_000L,
            closeAt = 1_794_010_000_000L,
            occursAt = 1_794_000_000_000L,
            occasion = "26NOV06DETBOS",
            canCloseEarly = true,
            exclusive = true,
            title = "Detroit",
            outcome = "0",
        )

    @Test
    fun `a ladder contract read off the wire is a 1-dollar contract with its strike, times and fee`() {
        val meta = requireNotNull(ladder.instrumentSpec("KALSHI:KXINXU_26NOV06H1500_T7425")).meta
        val terms = meta.derivative as EventTerms

        assertThat(meta.contractSize).isEqualByComparingTo("1")
        assertThat(meta.pointSize).isEqualByComparingTo("0.01")
        assertThat(meta.digits).isEqualTo(2)
        assertThat(meta.volumeStep).isEqualByComparingTo("0.01")
        assertThat(meta.volumeMin).isEqualByComparingTo("1")
        assertThat(meta.currency).isEqualTo("USD")
        assertThat(meta.tradeMode).isEqualTo(TradeMode.FULL)
        assertThat(terms.root).isEqualTo("KALSHI:KXINXU")
        assertThat(terms.event).isEqualTo("KXINXU-26NOV06H1500")
        assertThat(terms.predicate).isEqualTo(StrikePredicate(StrikeType.GREATER_OR_EQUAL, BigDecimal("7425"), null))
        assertThat(terms.openAtMs).isEqualTo(1_794_002_400_000L)
        assertThat(terms.closeAtMs).isEqualTo(1_794_006_000_000L)
        assertThat(terms.expiryMs).isEqualTo(1_794_006_000_000L)
        assertThat(terms.occursAtMs).isNull()
        assertThat(terms.occasion).isEqualTo("26NOV06H1500")
        assertThat(terms.frequency).isEqualTo("1h")
        assertThat(terms.title).isEqualTo("7,425 or above")
        assertThat(terms.payout).isEqualByComparingTo("1")
        assertThat(terms.outcome).isNull()
        assertThat(terms.fee)
            .isEqualTo(ProbabilityFee(BigDecimal("0.07"), BigDecimal("0.0175"), BigDecimal("0.01")))
    }

    @Test
    fun `a head-to-head contract has a custom strike, its game time, its trading mode and its outcome`() {
        val meta = requireNotNull(headToHead.instrumentSpec("KALSHI:KXNBAGAME_26NOV06DETBOS_DET")).meta
        val terms = meta.derivative as EventTerms

        assertThat(meta.tradeMode).isEqualTo(TradeMode.DISABLED)
        assertThat(terms.root).isEqualTo("KALSHI:KXNBAGAME")
        assertThat(terms.predicate).isEqualTo(StrikePredicate(StrikeType.CUSTOM, null, null))
        assertThat(terms.occursAtMs).isEqualTo(1_794_000_000_000L)
        assertThat(terms.canCloseEarly).isTrue()
        assertThat(terms.exclusive).isTrue()
        assertThat(terms.outcome).isEqualByComparingTo("0")
        assertThat(terms.fee).isNull()
    }

    @Test
    fun `absent flags read as false and a series code is written like a symbol`() {
        val terms =
            headToHead
                .copy(series = "KX-NBA", canCloseEarly = null, exclusive = null)
                .instrumentSpec("KALSHI:X")
                ?.meta
                ?.derivative as EventTerms

        assertThat(terms.root).isEqualTo("KALSHI:KX_NBA")
        assertThat(terms.canCloseEarly).isFalse()
        assertThat(terms.exclusive).isFalse()
    }

    @Test
    fun `the account finds an event by its symbol and the connector lists event products`() {
        fake.instruments[ladderCode] = ladder
        fake.instruments["KXNBAGAME-26NOV06DETBOS-DET"] = headToHead
        fake.instruments["AAPL"] = WireInstrument("AAPL", "equity", "USD", "1", "0.01", "1", "1")
        val account = account()

        val meta = requireNotNull(account.instrumentSpec("KALSHI:KXINXU_26NOV06H1500_T7425")).meta
        assertThat((meta.derivative as EventTerms).root).isEqualTo("KALSHI:KXINXU")
        assertThat(requireNotNull(account.instrumentSpec("KALSHI:AAPL")).meta.derivative).isNull()
        assertThat(GatewayConnector().spec.productTypes).contains(ProductType.EVENT)
    }
}
