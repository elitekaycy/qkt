package com.qkt.cli.golden

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The engine journal's market events must add up to the counts the manifest states. */
class GoldenEngineCountsTest {
    @TempDir
    lateinit var tmp: Path

    private val journal = "engine/journal-1.jsonl"

    private fun read(
        counts: String?,
        body: String = "{\"seq\":1,\"eventType\":\"com.qkt.events.OrderEvent\"}\n\n",
    ) {
        val file = tmp.resolve("bundle.zip")
        ZipOutputStream(Files.newOutputStream(file)).use { out ->
            out.putNextEntry(ZipEntry(journal))
            out.write(body.toByteArray())
            out.closeEntry()
        }
        val manifest = Json.parseToJsonElement(if (counts == null) "{}" else """{"counts":$counts}""").jsonObject
        ZipFile(file.toFile()).use { readMarketRecords(it, listOf(journal), manifest) }
    }

    private fun counts(
        ticks: Int = 0,
        warmup: Int = 0,
        candles: Int = 0,
        extra: String = "",
    ) = """{"ticks":$ticks,"warmupTicks":$warmup,"candles":$candles$extra}"""

    @Test
    fun `a manifest without counts is refused`() {
        assertThatThrownBy { read(null) }.hasMessage("golden bundle has no counts")
    }

    @Test
    fun `each count that differs from the journal is refused by name`() {
        assertThatThrownBy { read(counts(ticks = 1)) }.hasMessage("golden live tick count does not match manifest")
        assertThatThrownBy { read(counts(warmup = 2)) }.hasMessage("golden warmup tick count does not match manifest")
        assertThatThrownBy { read(counts(candles = 3)) }.hasMessage("golden candle count does not match manifest")
        assertThatThrownBy { read(counts(extra = ",\"streamCandles\":1")) }
            .hasMessage("golden stream candle count does not match manifest")
        assertThatThrownBy { read(counts(extra = ",\"strategyCandleEvaluations\":4")) }
            .hasMessage("golden strategy candle evaluation count does not match manifest")
    }

    @Test
    fun `a missing or malformed required count is refused`() {
        assertThatThrownBy { read("""{"ticks":0,"warmupTicks":0}""") }
            .hasMessage("missing numeric candles at manifest.json:1")
        assertThatThrownBy { read(counts(extra = ",\"streamCandles\":\"many\"")) }
            .hasMessage("invalid numeric streamCandles at manifest.json:1")
    }

    @Test
    fun `counts that agree with a journal holding no live ticks are still refused`() {
        assertThatThrownBy { read(counts()) }.hasMessage("golden bundle has no live ticks")
    }

    @Test
    fun `a journal repeating a sequence number is refused at the line`() {
        val body = "{\"seq\":7,\"eventType\":\"x\"}\n{\"seq\":7,\"eventType\":\"y\"}\n"

        assertThatThrownBy { read(counts(), body) }.hasMessage("duplicate engine sequence 7 in $journal:2")
    }
}
