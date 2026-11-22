package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.FuturesDirectory
import com.qkt.instrument.FuturesRoot
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.RollHistory
import com.qkt.marketdata.source.ExpandableLiveSource
import com.qkt.marketdata.source.NullMarketSource
import com.qkt.persistence.NoopStatePersistor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ContinuousWiringPlainTest {
    private val clock = FixedClock(0L)

    @Test
    fun `a session with no instrument registry serves its source as given and has no chains`() {
        val wiring =
            ContinuousWiring(listOf("BACKTEST:XAUUSD"), null, NullMarketSource, clock, NoopStatePersistor(), "gold")

        assertThat(wiring.chains).isNull()
        assertThat(wiring.source).isSameAs(NullMarketSource)
    }

    @Test
    fun `a lane bus bound before the session's mailbox exists is refused`() {
        val wiring = ContinuousWiring(emptyList(), null, NullMarketSource, clock, NoopStatePersistor(), "gold")

        assertThatThrownBy { wiring.bindLane(EventBus(clock, MonotonicSequenceGenerator())) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("a lane bus was bound before the session's mailbox existed")
    }

    private val directory =
        object : FuturesDirectory {
            override fun root(rootId: String): FuturesRoot? = null

            override fun catalog(rootId: String): ContractCatalog? = null

            override fun history(rootId: String): RollHistory? = null

            override fun rootOfContinuous(symbol: String): String? = "X:GC".takeIf { symbol.endsWith("@front") }
        }
    private val registry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? = null

            override fun futures(): FuturesDirectory = directory
        }

    @Test
    fun `a continuous stream whose roll histories are not on disk is refused at wiring`() {
        assertThatThrownBy {
            ContinuousWiring(listOf("X:GC@front"), registry, NullMarketSource, clock, NoopStatePersistor(), "gold")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("need their roll histories on disk")
    }

    @Test
    fun `a continuous stream beside a symbol set whose members move is refused at wiring, naming why`() {
        val moving = ExpandableLiveSource(NullMarketSource)
        assertThatThrownBy {
            ContinuousWiring(listOf("X:GC@front"), registry, moving, clock, NoopStatePersistor(), "gold")
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("beside a continuous futures stream")
            .hasMessageContaining("run them as two strategies")
    }
}
