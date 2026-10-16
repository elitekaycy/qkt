package com.qkt.marketdata.ingest.source

import com.qkt.marketdata.ingest.ImportRefusal
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ImportSourcesTest {
    @TempDir
    lateinit var dir: Path

    private fun text(input: ImportInput): String = TextDecoding.open(input.open()).use { it.readText() }

    @Test
    fun `opens gzip by its magic bytes whatever the file is called`() {
        val file = dir.resolve("ticks.dat")
        GZIPOutputStream(Files.newOutputStream(file)).use { it.write("a,b\n1,2\n".toByteArray()) }

        assertThat(text(ImportSources.expand(listOf(file)).inputs.single())).isEqualTo("a,b\n1,2\n")
    }

    @Test
    fun `a HistData zip yields its csv and skips the status report`() {
        val zip = dir.resolve("HISTDATA_COM_ASCII_EURUSD_M1_2017.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { z ->
            z.putNextEntry(ZipEntry("DAT_ASCII_EURUSD_M1_2017.csv"))
            z.write("20170102 020000;1;1;1;1;0\n".toByteArray())
            z.putNextEntry(ZipEntry("DAT_ASCII_EURUSD_M1_2017.txt"))
            z.write("Gap of 63s found".toByteArray())
            z.putNextEntry(ZipEntry("__MACOSX/._DAT.csv"))
            z.write(byteArrayOf(0))
        }

        assertThat(ImportSources.expand(listOf(zip)).inputs.map { it.name })
            .containsExactly("HISTDATA_COM_ASCII_EURUSD_M1_2017.zip!DAT_ASCII_EURUSD_M1_2017.csv")
    }

    @Test
    fun `a directory walk reads data files in name order and lists the rest as skipped`() {
        Files.writeString(dir.resolve("b.csv"), "x")
        Files.writeString(dir.resolve("a.csv"), "x")
        Files.writeString(dir.resolve("a.csv.ok"), "sha")
        Files.writeString(dir.resolve(".hidden.csv"), "x")

        val expansion = ImportSources.expand(listOf(dir))

        assertThat(expansion.inputs.map { it.name }).containsExactly("a.csv", "b.csv")
        assertThat(expansion.skipped.map { it.fileName.toString() }).containsExactly("a.csv.ok")
    }

    @Test
    fun `binary vendor formats are refused with the export to use`() {
        val bi5 = dir.resolve("13h_ticks.bi5").also { Files.write(it, byteArrayOf(1, 2, 3)) }
        val parquet = dir.resolve("bars.data").also { Files.write(it, "PAR1....".toByteArray()) }

        assertThatThrownBy {
            ImportSources.expand(listOf(bi5))
        }.isInstanceOf(ImportRefusal::class.java).hasMessageContaining("Dukascopy")
        assertThatThrownBy { ImportSources.expand(listOf(parquet)) }.hasMessageContaining("Parquet")
    }

    @Test
    fun `the digest names the file's exact bytes`() {
        val file = dir.resolve("x.csv").also { Files.writeString(it, "abc") }

        assertThat(
            ImportSources
                .expand(listOf(file))
                .inputs
                .single()
                .sha256,
        ).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    }

    @Test
    fun `decodes UTF-8 and UTF-16 by their marks, and BOM-less UTF-16 by its NUL bytes`() {
        val text = "<DATE>\t<TIME>\n2024.01.02\t00:00:00\n"
        val utf16le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val utf16be = text.toByteArray(Charsets.UTF_16BE)
        val utf8bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray()

        listOf(utf16le, utf16be, utf8bom).forEach {
            assertThat(TextDecoding.open(it.inputStream()).readText()).isEqualTo(text)
        }
    }

    @Test
    fun `a byte that is not UTF-8 fails instead of becoming a replacement character`() {
        val latin1 = "1,2é\n".toByteArray(Charsets.ISO_8859_1)

        assertThatThrownBy {
            TextDecoding.open(latin1.inputStream()).readText()
        }.isInstanceOf(CharacterCodingException::class.java)
        assertThat(TextDecoding.open(latin1.inputStream(), Charsets.ISO_8859_1).readText()).isEqualTo("1,2é\n")
    }
}
