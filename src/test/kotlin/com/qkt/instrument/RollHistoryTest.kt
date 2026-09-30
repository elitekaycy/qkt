package com.qkt.instrument

import java.nio.file.Path
import java.time.LocalTime
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RollHistoryTest {
    private val record = RollRecord(1_726_732_800_000L, "BTCUSDT_240927", "BTCUSDT_241227", "63012.4", "63790.1")

    @Test
    fun `the policy key ignores the adjustment`() {
        assertThat(RollPolicy(8, LocalTime.of(8, 0), PriceAdjustment.PANAMA).key).isEqualTo("8d@08:00")
        assertThat(RollPolicy(8, LocalTime.of(8, 0), PriceAdjustment.RATIO).key).isEqualTo("8d@08:00")
    }

    @Test
    fun `a record is found by instant and contract pair`() {
        val history = RollHistory("BINANCE_UM:BTCUSDT", "8d@08:00", listOf(record))
        assertThat(history.find(record.atMs, "BTCUSDT_240927", "BTCUSDT_241227")).isEqualTo(record)
        assertThat(history.find(record.atMs, "BTCUSDT_241227", "BTCUSDT_250328")).isNull()
    }

    @Test
    fun `non-positive reference prices are refused`() {
        assertThatThrownBy { record.copy(toPrice = "0") }.hasMessageContaining("toPrice")
    }

    @Test
    fun `a history round-trips through its file`(
        @TempDir dir: Path,
    ) {
        val store = RollHistoryStore(dir)
        val history = RollHistory("BINANCE_UM:BTCUSDT", "8d@08:00", listOf(record))
        store.write(history)
        assertThat(store.path("BINANCE_UM:BTCUSDT")).isEqualTo(dir.resolve("contracts/BINANCE_UM/BTCUSDT.rolls.json"))
        assertThat(store.read("BINANCE_UM:BTCUSDT")).isEqualTo(history)
        assertThat(store.read("CME:ES")).isNull()
    }
}
