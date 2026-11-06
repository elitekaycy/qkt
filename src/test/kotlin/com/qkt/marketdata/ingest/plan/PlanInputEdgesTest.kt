package com.qkt.marketdata.ingest.plan

import com.qkt.instrument.StandardInstrumentRegistry
import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.rows.CellShape
import com.qkt.marketdata.ingest.rows.DecimalGuess
import com.qkt.marketdata.ingest.store.ImportProvenance
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PlanInputEdgesTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a preset that is neither built in nor a file is refused listing the built-ins`() {
        assertThatThrownBy { PresetLibrary.resolve("no-such-preset") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("no preset 'no-such-preset' (built-in: ")
            .hasMessageContaining("or give a .yaml file")
    }

    @Test
    fun `a single time column in YAML reads as a one-column list`() {
        val descriptor = DescriptorYaml.decode("kind: bars\ntime: Date\n", "custom.yaml")

        assertThat(descriptor.time).containsExactly("Date")
        assertThat(descriptor.kind).isEqualTo(IngestKind.BARS)
    }

    @Test
    fun `a symbol that cannot be a file name is refused`() {
        assertThatThrownBy { ImportTarget.of("EUR USD", StandardInstrumentRegistry, null) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("cannot be a file name in the store")
    }

    @Test
    fun `numbers written with both decimal separators are refused`() {
        assertThatThrownBy { DecimalGuess.of(listOf(listOf("1,5", "2.5")), ';') }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("both ',' and '.'")
    }

    @Test
    fun `a cell's shape is judged with a dot decimal by default`() {
        assertThat(CellShape.of("1.5")).isEqualTo(CellShape.NUMBER)
        assertThat(CellShape.of("abc")).isEqualTo(CellShape.TEXT)
    }

    @Test
    fun `corrupt import provenance is refused naming its file`() {
        val file = Files.writeString(dir.resolve(ImportProvenance.FILE), "{broken")

        assertThatThrownBy { ImportProvenance.read(dir) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("corrupt import provenance at $file")
    }
}
