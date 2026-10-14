package com.qkt.marketdata.ingest.rows

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.source.ImportSources
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RowReadingTest {
    @TempDir
    lateinit var dir: Path

    private fun rows(
        name: String,
        text: String,
        rowsPath: List<String> = emptyList(),
    ): Pair<List<ImportRow>, RowSkips> {
        val input = ImportSources.expand(listOf(dir.resolve(name).also { Files.writeString(it, text) })).inputs.single()
        val layout = InputLayout.of(input, null, rowsPath, null, null, null)
        val out = mutableListOf<ImportRow>()
        val skips = layout.read(input) { out += it }
        return out to skips
    }

    @Test
    fun `a header repeated mid-file by concatenation is skipped and counted`() {
        val (rows, skips) = rows("a.csv", "t,v\n1700000000000,1\nt,v\n1700000060000,2\n")

        assertThat(rows.map { it.cell(CellRef.Index(1)) }).containsExactly("1", "2")
        assertThat(skips.repeatedHeaders).isEqualTo(1)
    }

    @Test
    fun `a short row refuses with its line instead of being padded`() {
        val good = (0 until 20).joinToString("") { "${1_700_000_000_000L + it * 60_000},1,2\n" }

        assertThatThrownBy { rows("a.csv", "t,o,c\n${good}1700001200000,1\n") }
            .isInstanceOf(ImportRefusal::class.java)
            .satisfies({ assertThat((it as ImportRefusal).lines.single()).startsWith("a.csv:22:") })
    }

    @Test
    fun `quoted fields keep their delimiters and doubled quotes`() {
        assertThat(splitCsvLine("a,\"b,c\",\"say \"\"hi\"\"\"", ',')).containsExactly("a", "b,c", "say \"hi\"")
        assertThatThrownBy { splitCsvLine("a,\"b", ',') }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `JSON arrays of arrays, arrays of objects, nested rows and NDJSON all read`() {
        val ccxt = rows("c.json", "[[1504541580000, 4235.4], [1504541640000, 4230.7]]").first
        val nested = rows("p.json", "{\"results\":[{\"t\":1,\"mid\":{\"o\":\"1.1\"}}]}", listOf("results")).first
        val ndjson = rows("n.json", "{\"t\":1,\"v\":2}\n{\"t\":3,\"v\":4}\n").first

        assertThat(ccxt.map { it.cell(CellRef.Index(1)) }).containsExactly("4235.4", "4230.7")
        assertThat(nested.single().cell(CellRef.Path(listOf("mid", "o")))).isEqualTo("1.1")
        assertThat(ndjson.map { it.cell(CellRef.Path(listOf("v"))) }).containsExactly("2", "4")
    }

    @Test
    fun `an object of rows keyed by time exposes the key`() {
        val json = "{\"Time Series (5min)\":{\"2026-10-13 19:55:00\":{\"1. open\":\"230.69\"}}}"
        val row = rows("av.json", json, listOf("Time Series (5min)")).first.single()

        assertThat(row.cell(CellRef.Path(listOf("@key")))).isEqualTo("2026-10-13 19:55:00")
        assertThat(row.cell(CellRef.Path(listOf("1. open")))).isEqualTo("230.69")
    }
}
