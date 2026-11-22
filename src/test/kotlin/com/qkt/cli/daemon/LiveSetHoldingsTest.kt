package com.qkt.cli.daemon

import com.qkt.cli.daemon.MovingSetListing.NOW
import com.qkt.cli.daemon.MovingSetListing.contract
import com.qkt.cli.daemon.MovingSetListing.held
import com.qkt.cli.daemon.MovingSetListing.listing
import com.qkt.common.Side
import com.qkt.dsl.sets.SetCatalog
import com.qkt.dsl.sets.SetOwnership
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.persistence.FileStatePersistor
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * What a restarting live session declares for a moving set: the positions and working orders the set itself had,
 * never another rule's, and never a contract the venue no longer trades.
 */
class LiveSetHoldingsTest {
    @TempDir
    lateinit var tmp: Path

    private val listing = listing(contract("A", 0, 2), contract("B", 1, 3), contract("LATE", 30, 40))
    private lateinit var persistor: FileStatePersistor
    private lateinit var file: Path

    @BeforeEach
    fun start() {
        persistor = FileStatePersistor(tmp.resolve("persist"))
        file = MovingSetListing.strategy(tmp)
    }

    private fun declared(
        strategy: Path = file,
        catalog: SetCatalog = listing,
    ): List<String> = liveStrategyAst(strategy, catalog, NOW, persistor).streams.map { it.qktSymbol }

    @Test
    fun `a restart declares the positions the set itself held, picked or not, and no other`() {
        persistor.saveLegBook("rolling", "GW:A", held("GW:A"))
        assertThat(declared()).containsExactly("GW:A", "GW:B")
        persistor.saveLegBook("rolling", "GW:LATE", held("GW:LATE"))
        assertThatThrownBy { declared() }.hasMessageContaining("strategy 'rolling' holds GW:LATE")
        SetOwnership.save(persistor, "rolling", "m", listOf("A", "LATE"))
        assertThat(declared()).containsExactly("GW:A", "GW:B", "GW:LATE")
        persistor.saveLegBook("rolling", "OTHER:X", held("OTHER:X"))
        assertThat(declared()).doesNotContain("OTHER:X")
    }

    @Test
    fun `a position a plain stream holds on the set's venue is never adopted by the set`() {
        val mixed =
            tmp.resolve("mixed.qkt").also {
                val text = file.toFile().readText()
                Files.writeString(it, text.replace("SYMBOLS\n", "SYMBOLS\n    x = GW:FOO EVERY 1h\n"))
            }
        persistor.saveLegBook("rolling", "GW:FOO", held("GW:FOO"))
        assertThat(liveStrategyAst(mixed, listing, NOW, persistor).streams.map { it.alias })
            .containsExactly("x", "m.A", "m.B")
    }

    @Test
    fun `a member with only an entry order working is declared, so its fill is managed`() {
        val order = OrderRequest.Market("rolling-1", "GW:LATE", Side.BUY, BigDecimal.ONE, TimeInForce.GTC, NOW)
        persistor.savePendingOrders("rolling", mapOf(order.id to order))
        SetOwnership.save(persistor, "rolling", "m", listOf("LATE"))
        assertThat(declared()).containsExactly("GW:A", "GW:B", "GW:LATE")
    }

    @Test
    fun `a held contract that expired or left the listing does not stop the restart and is not declared`() {
        val catalog = listing(contract("A", 0, 2), contract("B", 1, 3), contract("OLD", -5, -1))
        persistor.saveLegBook("rolling", "GW:OLD", held("GW:OLD"))
        persistor.saveLegBook("rolling", "GW:GONE", held("GW:GONE"))
        SetOwnership.save(persistor, "rolling", "m", listOf("OLD", "GONE"))
        assertThat(declared(catalog = catalog)).containsExactly("GW:A", "GW:B")
    }

    @Test
    fun `a set no close picks a member for still starts over the position it holds`() {
        val catalog = listing(contract("LATE", 30, 40))
        assertThatThrownBy { declared(catalog = catalog) }.hasMessageContaining("at any close of the run")
        persistor.saveLegBook("rolling", "GW:LATE", held("GW:LATE"))
        SetOwnership.save(persistor, "rolling", "m", listOf("LATE"))
        assertThat(declared(catalog = catalog)).containsExactly("GW:LATE")
    }
}
