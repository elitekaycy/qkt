package com.qkt.marketdata.store

import com.qkt.marketdata.source.MarketRequest
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DefaultDataStoreOverlapTest {
    @TempDir lateinit var dir: Path

    @Test
    fun `symbols whose cached ranges never overlap are refused without a range`() {
        val manifests = ManifestStore(dir)
        manifests.write(Manifest(symbol = "A", ranges = listOf(DayRange("2024-01-15", "2024-01-16"))))
        manifests.write(Manifest(symbol = "B", ranges = listOf(DayRange("2024-02-01", "2024-02-02"))))

        assertThatThrownBy { DefaultDataStore(root = dir).openFeed(MarketRequest(symbols = listOf("A", "B"))) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("have no overlapping cached date range")
    }

    @Test
    fun `a store from the environment is rooted at the resolved data root`() {
        assertThat(DefaultDataStore.fromEnv().root).isEqualTo(DataRoot.resolve())
    }
}
