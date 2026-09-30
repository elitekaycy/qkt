package com.qkt.instrument

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ContractCatalogStoreTest {
    @Test
    fun `a catalog round-trips through its file`(
        @TempDir dir: Path,
    ) {
        val store = ContractCatalogStore(dir)
        val catalog =
            ContractCatalog(
                "BINANCE_UM:BTCUSDT",
                listOf(ListedContract("BTCUSDT_240927", 1_727_424_000_000L, "65528.1")),
            )
        store.write(catalog)
        assertThat(store.path("BINANCE_UM:BTCUSDT")).isEqualTo(dir.resolve("contracts/BINANCE_UM/BTCUSDT.json"))
        assertThat(store.read("BINANCE_UM:BTCUSDT")).isEqualTo(catalog)
    }

    @Test
    fun `a missing catalog reads as null`(
        @TempDir dir: Path,
    ) {
        assertThat(ContractCatalogStore(dir).read("CME:ES")).isNull()
        assertThat(Files.exists(dir.resolve("contracts"))).isFalse()
    }

    @Test
    fun `a corrupt catalog names its file`(
        @TempDir dir: Path,
    ) {
        val store = ContractCatalogStore(dir)
        Files.createDirectories(store.path("CME:ES").parent)
        Files.writeString(store.path("CME:ES"), """{"root":"CME:ES","contracts":[{"symbol":"ESZ6","expiryMs":-1}]}""")
        assertThatThrownBy { store.read("CME:ES") }.hasMessageContaining("ES.json").hasMessageContaining("expiryMs")
    }
}
