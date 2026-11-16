package com.qkt.marketdata.ingest.rows

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.source.ImportSources
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Row reading edges: blank lines, trailing delimiters, nested JSON cells and undecodable bytes. */
class RowReadingEdgesTest {
    @TempDir
    lateinit var dir: Path

    private fun read(path: Path): Pair<List<ImportRow>, RowSkips> {
        val input = ImportSources.expand(listOf(path)).inputs.single()
        val layout = InputLayout.of(input, null, emptyList(), null, null, null)
        val out = mutableListOf<ImportRow>()
        val skips = layout.read(input) { out += it }
        return out to skips
    }

    private fun write(
        name: String,
        text: String,
    ): Path = dir.resolve(name).also { Files.writeString(it, text) }

    @Test
    fun `blank lines between rows are skipped and counted`() {
        val (rows, skips) = read(write("a.csv", "t,v\n1700000000000,1\n\n1700000060000,2\n"))

        assertThat(rows.map { it.cell(CellRef.Index(1)) }).containsExactly("1", "2")
        assertThat(skips.blankLines).isEqualTo(1)
    }

    @Test
    fun `a delimiter closing every line does not add an empty column`() {
        val (rows, _) = read(write("a.csv", "t,v,\n1700000000000,1,\n1700000060000,2,\n"))

        assertThat(rows.map { it.cell(CellRef.Index(1)) }).containsExactly("1", "2")
        assertThat(rows.map { it.cell(CellRef.Index(2)) }).containsOnlyNulls()
    }

    @Test
    fun `a JSON cell that is an object or an array has no text`() {
        val (rows, _) = read(write("p.json", "[{\"t\":1,\"mid\":{\"o\":\"1.1\"},\"v\":[1,2]}]"))
        val row = rows.single()

        assertThat(row.cell(CellRef.Path(listOf("mid")))).isNull()
        assertThat(row.cell(CellRef.Path(listOf("v")))).isNull()
        assertThat(row.cell(CellRef.Path(listOf("mid", "o")))).isEqualTo("1.1")
    }

    @Test
    fun `bytes that are not UTF-8 refuse and suggest latin-1`() {
        val bytes = "t,v\n1700000000000,caf".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + "\n".toByteArray()
        val path = dir.resolve("a.csv").also { Files.write(it, bytes) }

        assertThatThrownBy { read(path) }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("a.csv is not valid UTF-8 or UTF-16 text")
            .hasMessageContaining("pass --encoding latin-1 if it is Latin-1")
    }
}
