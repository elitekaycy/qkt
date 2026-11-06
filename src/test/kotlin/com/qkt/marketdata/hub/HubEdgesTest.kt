package com.qkt.marketdata.hub

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class HubEdgesTest {
    @TempDir
    lateinit var root: Path

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/hub/$name")) { "missing fixture $name" }.use { it.readAllBytes() }

    @Test
    fun `a snapshot file reads to the same records and header as its bytes`() {
        val bytes = fixture("cal.high_impact.qkh")
        val file = Files.write(root.resolve("cal.high_impact.qkh"), bytes)

        assertThat(HubSnapshotFormat.read(file)).isEqualTo(HubSnapshotFormat.decode(bytes).second)
        assertThat(HubSnapshotFormat.readHeader(file).dataset).isEqualTo("cal.high_impact")
    }

    @Test
    fun `a few bytes are too short to be a snapshot`() {
        assertThatThrownBy { HubSnapshotFormat.decode(ByteArray(3)) }
            .isInstanceOf(HubSnapshotFormat.HubFormatException::class.java)
            .hasMessageContaining("too short")
            .hasMessageContaining("3 bytes")
    }

    @Test
    fun `a journal line that is JSON but not an object is refused`() {
        assertThatThrownBy { HubJournalCodec.parse("[1,2]") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("hub journal line is not a JSON object")
    }

    @Test
    fun `a hub key that is not an integer is refused naming it`() {
        assertThatThrownBy { HubStoreConfig.parse(mapOf("min_lag_ms" to "soon")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("hub.min_lag_ms must be an integer, got 'soon'")
    }

    @Test
    fun `the hub source serves its own prefix and sees a store only with a journal`() {
        val source = HubMarketSource(root)

        assertThat(source.supports("HUB:cal.high_impact:US:impact")).isTrue()
        assertThat(source.supports("EXNESS:XAUUSD")).isFalse()
        assertThat(source.storeExists()).isFalse()
        Files.createDirectories(root.resolve("journal"))
        assertThat(source.storeExists()).isTrue()
    }

    @Test
    fun `a day file holding only an unfinished line reads as nothing yet`() {
        val dir = Files.createDirectories(root.resolve("journal").resolve("cal.high_impact"))
        Files.writeString(dir.resolve("2026-09-10.ndjson"), "{\"dataset\":\"cal.high_impact\"")
        val journal = HubJournal(root, "cal.high_impact")

        assertThat(journal.readAll()).isEmpty()
        assertThat(journal.skipped).isZero()
    }
}
