package com.qkt.marketdata.hub

import com.qkt.common.TimeRange
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The cross-language contract: bytes written by the qkt-data-hub (Python) must decode here to the
 * same facts, or the engine and the store silently disagree about history.
 *
 * The fixtures under `src/test/resources/hub` are real output from the hub's own writer and
 * compiler, not hand-built. A format change on either side breaks this test, which is the point:
 * the two implementations are only as compatible as something checks.
 */
class HubFormatTest {
    private val effectiveAt = 1_789_043_400_000L

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/hub/$name")) { "missing fixture $name" }.use { it.readAllBytes() }

    private fun storeWithJournal(root: Path): Path {
        val dir = root.resolve("journal").resolve("cal.high_impact")
        dir.createDirectories()
        dir.resolve("2026-09-10.ndjson").writeBytes(fixture("cal.high_impact.ndjson"))
        return root
    }

    @Test
    fun `decodes a snapshot the hub wrote`() {
        val (header, records) = HubSnapshotFormat.decode(fixture("cal.high_impact.qkh"))

        assertThat(header.dataset).isEqualTo("cal.high_impact")
        assertThat(header.recordCount).isEqualTo(3)
        // A free-text title is not a strategy input, so the hub never stores it in a snapshot.
        assertThat(header.fieldNames).containsExactlyInAnyOrder("impact", "forecast", "actual", "surprise")
        assertThat(records).hasSize(3)
    }

    @Test
    fun `snapshot preserves provenance so a record equals its journalled original`() {
        val (_, records) = HubSnapshotFormat.decode(fixture("cal.high_impact.qkh"))
        assertThat(records.map { it.source }.distinct()).containsExactly("forexfactory")
    }

    @Test
    fun `an unknown value decodes to null rather than zero`() {
        val (_, records) = HubSnapshotFormat.decode(fixture("cal.high_impact.qkh"))
        val preRelease = records.first { it.scope == "USD" && it.revision == 1 }
        assertThat(preRelease.fields["actual"]).isNull()
        assertThat(preRelease.fields["surprise"]).isNull()
        assertThat(preRelease.fields["forecast"]).isEqualByComparingTo(BigDecimal("0.2"))
    }

    @Test
    fun `decimal values survive the scaled integer round trip exactly`() {
        val (_, records) = HubSnapshotFormat.decode(fixture("cal.high_impact.qkh"))
        val released = records.first { it.scope == "USD" && it.revision == 2 }
        assertThat(released.fields["actual"]).isEqualByComparingTo(BigDecimal("0.3"))
        assertThat(released.fields["surprise"]).isEqualByComparingTo(BigDecimal("0.1"))
        assertThat(records.first { it.scope == "EUR" }.fields["forecast"]).isEqualByComparingTo(BigDecimal("2.65"))
    }

    @Test
    fun `a flipped byte is refused rather than read as different facts`() {
        val corrupted = fixture("cal.high_impact.qkh").copyOf()
        corrupted[corrupted.size / 2] = (corrupted[corrupted.size / 2].toInt() xor 0xFF).toByte()
        assertThatThrownBy { HubSnapshotFormat.decode(corrupted) }
            .isInstanceOf(HubSnapshotFormat.HubFormatException::class.java)
            .hasMessageContaining("checksum")
    }

    @Test
    fun `a truncated snapshot is refused`() {
        val short = fixture("cal.high_impact.qkh").copyOf(40)
        assertThatThrownBy { HubSnapshotFormat.decode(short) }
            .isInstanceOf(HubSnapshotFormat.HubFormatException::class.java)
    }

    @Test
    fun `journal and snapshot agree on the same facts`(
        @TempDir tmp: Path,
    ) {
        val (_, fromSnapshot) = HubSnapshotFormat.decode(fixture("cal.high_impact.qkh"))
        val fromJournal = HubJournal(storeWithJournal(tmp), "cal.high_impact").readAll()

        assertThat(fromJournal).hasSameSizeAs(fromSnapshot)
        val journalUsd = fromJournal.first { it.scope == "USD" && it.revision == 2 }
        val snapshotUsd = fromSnapshot.first { it.scope == "USD" && it.revision == 2 }
        assertThat(journalUsd.knownAt).isEqualTo(snapshotUsd.knownAt)
        assertThat(journalUsd.effectiveAt).isEqualTo(snapshotUsd.effectiveAt)
        assertThat(journalUsd.source).isEqualTo(snapshotUsd.source)
        assertThat(journalUsd.fields["actual"]).isEqualByComparingTo(snapshotUsd.fields["actual"])
        assertThat(journalUsd.fields["surprise"]).isEqualByComparingTo(snapshotUsd.fields["surprise"])
    }

    @Test
    fun `a torn trailing line is ignored until it is complete`(
        @TempDir tmp: Path,
    ) {
        val root = storeWithJournal(tmp)
        val file = root.resolve("journal").resolve("cal.high_impact").resolve("2026-09-10.ndjson")
        val complete = HubJournal(root, "cal.high_impact").readAll().size
        Files.writeString(file, Files.readString(file) + """{"dataset":"cal.high""")

        assertThat(HubJournal(root, "cal.high_impact").readAll()).hasSize(complete)
    }

    @Test
    fun `a malformed line is skipped and counted rather than taking the session down`(
        @TempDir tmp: Path,
    ) {
        val root = storeWithJournal(tmp)
        val file = root.resolve("journal").resolve("cal.high_impact").resolve("2026-09-10.ndjson")
        Files.writeString(file, Files.readString(file) + "{\"not\":\"a record\"}\n")

        val journal = HubJournal(root, "cal.high_impact")
        assertThat(journal.readAll()).hasSize(3)
        assertThat(journal.skipped).isEqualTo(1L)
    }

    @Test
    fun `ticks are stamped at known_at, never at the instant the fact describes`(
        @TempDir tmp: Path,
    ) {
        val root = storeWithJournal(tmp)
        val source = HubMarketSource(root)
        val symbol = HubStreamSymbol("cal.high_impact", "USD", "forecast").symbol
        val ticks =
            source
                .ticks(symbol, TimeRange(Instant.ofEpochMilli(0), Instant.ofEpochMilli(Long.MAX_VALUE / 2)))
                .toList()

        assertThat(ticks).isNotEmpty
        // Every USD record in the fixture shares one effective_at but has its own known_at; if the
        // source stamped effective_at, a strategy would see the released number at the moment the
        // release was merely scheduled.
        assertThat(ticks.map { it.timestamp }).doesNotContain(effectiveAt - 1)
        assertThat(ticks.first().timestamp).isEqualTo(effectiveAt)
        assertThat(ticks.map { it.timestamp }).isSorted
    }

    @Test
    fun `a field the hub recorded as unknown produces no tick at all`(
        @TempDir tmp: Path,
    ) {
        val root = storeWithJournal(tmp)
        val source = HubMarketSource(root)
        val range = TimeRange(Instant.ofEpochMilli(0), Instant.ofEpochMilli(Long.MAX_VALUE / 2))

        val actual = source.ticks(HubStreamSymbol("cal.high_impact", "USD", "actual").symbol, range).toList()
        val forecast = source.ticks(HubStreamSymbol("cal.high_impact", "USD", "forecast").symbol, range).toList()

        // Two USD records exist; only the second carries an actual. Emitting a tick for the
        // unknown one would make "no data yet" indistinguishable from a real value.
        assertThat(forecast).hasSize(2)
        assertThat(actual).hasSize(1)
        assertThat(actual.single().price).isEqualByComparingTo(BigDecimal("0.3"))
    }

    @Test
    fun `scope selects its own series and never mixes two`(
        @TempDir tmp: Path,
    ) {
        val root = storeWithJournal(tmp)
        val source = HubMarketSource(root)
        val range = TimeRange(Instant.ofEpochMilli(0), Instant.ofEpochMilli(Long.MAX_VALUE / 2))

        val eur = source.ticks(HubStreamSymbol("cal.high_impact", "EUR", "forecast").symbol, range).toList()
        assertThat(eur).hasSize(1)
        assertThat(eur.single().price).isEqualByComparingTo(BigDecimal("2.65"))
    }

    @Test
    fun `a minimum lag delays visibility by exactly that much`(
        @TempDir tmp: Path,
    ) {
        val root = storeWithJournal(tmp)
        val symbol = HubStreamSymbol("cal.high_impact", "USD", "forecast").symbol
        val range = TimeRange(Instant.ofEpochMilli(0), Instant.ofEpochMilli(Long.MAX_VALUE / 2))

        val plain = HubMarketSource(root).ticks(symbol, range).first().timestamp
        val lagged = HubMarketSource(root, HubPolicy(minLagMs = 60_000)).ticks(symbol, range).first().timestamp
        assertThat(lagged - plain).isEqualTo(60_000L)
    }

    @Test
    fun `a hub stream symbol round trips through parse`() {
        val scoped = HubStreamSymbol("cal.high_impact", "USD", "surprise")
        assertThat(HubStreamSymbol.parse(scoped.symbol)).isEqualTo(scoped)

        val unscoped = HubStreamSymbol("rates.us.dfii10", null, "value")
        assertThat(HubStreamSymbol.parse(unscoped.symbol)).isEqualTo(unscoped)
    }

    @Test
    fun `a symbol without a field is rejected`() {
        assertThatThrownBy { HubStreamSymbol.parse("HUB:cal.high_impact") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
