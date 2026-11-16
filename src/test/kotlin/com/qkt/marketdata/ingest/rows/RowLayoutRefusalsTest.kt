package com.qkt.marketdata.ingest.rows

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.source.ImportSources
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Layouts qkt cannot read rows from refuse and say what to pass instead. */
class RowLayoutRefusalsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `two delimiters that split the file equally well refuse and show a tab as an escape`() {
        assertThatThrownBy { CsvDialectSniffer.sniff(listOf("1\t2|3", "4\t5|6", "7\t8|9")) }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("cannot tell the delimiter: ")
            .hasMessageContaining("'\\t' scores ")
            .hasMessageContaining("'|' scores ")
            .hasMessageContaining("; pass --delimiter")
    }

    @Test
    fun `a rows path that ends at a scalar refuses`() {
        val file = dir.resolve("p.json").also { Files.writeString(it, "{\"results\":42}") }
        val input = ImportSources.expand(listOf(file)).inputs.single()

        assertThatThrownBy {
            InputLayout.of(input, null, listOf("results"), null, null, null).read(input) {}
        }.isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("p.json: rows must be a JSON array or object, found 42")
    }
}
