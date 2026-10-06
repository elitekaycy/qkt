package com.qkt.app

import com.qkt.broker.BrokerFactory
import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.derivatives.options.chain.OptionChainFixture
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.compile.PendingStacks
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.positions.PositionProvider
import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Live broker routing for a strategy that feeds an option root and chain analytics streams. */
class SessionBrokersOptionRoutesTest {
    private class Streams(
        override val declaredStreams: Map<String, HubKey>,
    ) : DslCompiledStrategy {
        override val multiPositionPerSymbolSymbols: Set<String> = emptySet()
        override val retentionByKey: Map<HubKey, Int> = emptyMap()
        override val pendingStacks: PendingStacks = PendingStacks()

        override fun bindToHub(
            hub: CandleHub,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {}

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {}
    }

    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val tracker = MarketPriceTracker()
    private val positions =
        object : PositionProvider {
            override fun positionFor(symbol: String) = null

            override fun allPositions() = emptyMap<String, com.qkt.positions.Position>()
        }
    private val built = mutableListOf<String>()
    private val deribit: BrokerFactory = { bus, clock, tracker, _, _ ->
        built += "deribit"
        PaperBroker(bus, clock, tracker)
    }
    private val feeds =
        mapOf(
            "chain" to HubKey("OPTIONS", "DERIBIT.BTC_USDC", "1m"),
            "iv" to HubKey("CHAIN", "DERIBIT.BTC_USDC.atm_iv.30d", "1m"),
        )

    private fun route(
        fixture: OptionChainFixture,
        streams: Map<String, HubKey>,
        factories: Map<String, BrokerFactory>,
    ) = SessionBrokers(listOf("s" to Streams(streams)), emptyList(), factories, fixture.registry)
        .buildBroker(PaperBroker(bus, clock, tracker), bus, clock, tracker, positions)

    @Test
    fun `a fed root needs only its venue's account, which takes every catalogued contract of the root`(
        @TempDir dir: Path,
    ) {
        val fixture = OptionChainFixture(dir)

        val broker = route(fixture, feeds, mapOf("deribit" to deribit))

        assertThat(built).containsExactly("deribit")
        assertThat(broker.supports(fixture.symbol)).isTrue()
        assertThat(broker.supports("DERIBIT:BTC_USDC_2OCT26_99000_C")).isFalse()
        assertThat(broker.supports("DERIBIT:ETH_USDC_2OCT26_3000_C")).isFalse()
    }

    @Test
    fun `a declared contract and a fed root of one account share one broker`(
        @TempDir dir: Path,
    ) {
        val fixture = OptionChainFixture(dir)
        val streams = feeds + ("perp" to HubKey("DERIBIT", "BTC_USDC_PERPETUAL", "1m"))

        val broker = route(fixture, streams, mapOf("deribit" to deribit))

        assertThat(built).containsExactly("deribit")
        assertThat(broker.supports("DERIBIT:BTC_USDC_PERPETUAL")).isTrue()
        assertThat(broker.supports(fixture.symbol)).isTrue()
    }

    @Test
    fun `a fed root whose venue has no account fails before any broker is built`(
        @TempDir dir: Path,
    ) {
        val fixture = OptionChainFixture(dir)

        assertThatThrownBy { route(fixture, feeds, mapOf("okx" to deribit)) }.hasMessageContaining("[deribit]")
        assertThat(built).isEmpty()
    }
}
