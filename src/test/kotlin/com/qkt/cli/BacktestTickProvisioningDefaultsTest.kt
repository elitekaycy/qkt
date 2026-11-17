package com.qkt.cli

import com.qkt.backtest.IncompleteDataException
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.store.BinaryBarStore
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BacktestTickProvisioningDefaultsTest {
    private fun stores(root: Path) =
        BacktestSetup.stores(Args(arrayOf("backtest")), root.toString(), fetcherOverride = null)

    private fun barReplay(root: Path) =
        BacktestBarReplay.BarReplayConfig(
            forceBars = false,
            tickFills = false,
            binaryBarStore = BinaryBarStore(root),
            finestDeclared = emptyMap(),
            barWindows = emptyMap(),
        )

    private val window = Instant.parse("2024-01-04T00:00:00Z") to Instant.parse("2024-01-05T00:00:00Z")

    @Test
    fun `nothing to replay provisions nothing under the built-in calendars`(
        @TempDir root: Path,
    ) {
        val stores = stores(root)

        BacktestTickProvisioning.provision(
            emptyList(),
            NoopInstrumentRegistry,
            barReplay(root),
            stores.ticks to stores.bars,
            window,
            noFetch = true,
            allowIncomplete = false,
        )
    }

    @Test
    fun `a symbol no stream declares is refused without a build-bars hint`(
        @TempDir root: Path,
    ) {
        val stores = stores(root)

        assertThatThrownBy {
            BacktestTickProvisioning.provision(
                listOf("BACKTEST:XAUUSD"),
                NoopInstrumentRegistry,
                barReplay(root),
                stores.ticks to stores.bars,
                window,
                noFetch = true,
                allowIncomplete = false,
            )
        }.isInstanceOf(IncompleteDataException::class.java)
            .satisfies(
                { assertThat(it.message).contains("No built bars cover this window.").doesNotContain("build-bars") },
            )
    }
}
