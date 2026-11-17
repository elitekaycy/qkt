package com.qkt.marketdata.ingest.store

import com.qkt.common.WireAsserts.decodes
import com.qkt.common.WireAsserts.refusesWithout
import com.qkt.common.WireAsserts.refusesWrongType
import com.qkt.common.WireAsserts.roundTrips
import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ImportProvenanceSerializationTest {
    private val input = ImportedInput("a.csv", "ab")
    private val bare =
        ImportEntry("e1", listOf(input), "p", "tick", "XAU", rows = 5L, records = 4L, qkt = "1", importedAt = "t")
    private val body =
        """{"id":"e1","inputs":[],"preset":"p","kind":"tick","symbol":"XAU","rows":5,"records":4,"qkt":"1","importedAt":"t"}"""

    @Test
    fun `an input needs its name and digest`() {
        roundTrips(input)
        refusesWithout<ImportedInput>("""{"name":"n","sha256":"s"}""", "name", "sha256")
        refusesWrongType<ImportedInput>("""{"name":"n","sha256":"s"}""", "name", "sha256")
    }

    @Test
    fun `an entry round trips with every optional set and none`() {
        roundTrips(bare)
        roundTrips(
            bare.copy(
                clock = "utc",
                clockEvidence = "ev",
                basis = "bid",
                volume = "real",
                timeframe = "1m",
                notes = listOf("n"),
                comparisons = listOf("c"),
            ),
        )
        decodes(body, bare.copy(inputs = emptyList()))
    }

    @Test
    fun `an entry names what is missing or mistyped`() {
        refusesWithout<ImportEntry>(
            body,
            "id",
            "inputs",
            "preset",
            "kind",
            "symbol",
            "rows",
            "records",
            "qkt",
            "importedAt",
        )
        refusesWrongType<ImportEntry>(
            body,
            "clock",
            "clockEvidence",
            "basis",
            "volume",
            "timeframe",
            "notes",
            "comparisons",
            "rows",
            "records",
        )
    }

    @Test
    fun `provenance round trips empty and full and finds entries by key`() {
        val empty = ImportProvenance()
        roundTrips(empty)
        val full = ImportProvenance("v2", listOf(bare), mapOf("2024-01-01" to "e1", "2024-01-02" to "gone"))
        roundTrips(full)
        decodes("{}", empty)
        assertThat(full.entryFor("2024-01-01")).isEqualTo(bare)
        assertThat(full.entryFor("2024-01-02")).isNull()
        assertThat(full.entryFor("nope")).isNull()
        refusesWrongType<ImportProvenance>("{}", "schema", "imports", "days")
    }

    @Test
    fun `reading a corrupt provenance file names the file`() {
        val dir = Files.createTempDirectory("prov")
        assertThat(ImportProvenance.read(dir)).isEqualTo(ImportProvenance())
        Files.writeString(dir.resolve(ImportProvenance.FILE), """{"imports":5}""")
        assertThatThrownBy { ImportProvenance.read(dir) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("corrupt import provenance at")
    }

    @Test
    fun `recording drops entries no day points at and keeps days sorted`() {
        val dir = Files.createTempDirectory("prov")
        ImportProvenance.record(dir, bare, listOf("b", "a"))
        ImportProvenance.record(dir, bare.copy(id = "e2"), listOf("b"))
        val read = ImportProvenance.read(dir)
        assertThat(read.days).containsExactly(
            org.assertj.core.api.Assertions
                .entry("a", "e1"),
            org.assertj.core.api.Assertions
                .entry("b", "e2"),
        )
        assertThat(read.imports.map { it.id }).containsExactlyInAnyOrder("e1", "e2")
        ImportProvenance.record(dir, bare.copy(id = "e3"), listOf("a", "b"))
        assertThat(ImportProvenance.read(dir).imports.map { it.id }).containsExactly("e3")
    }
}
