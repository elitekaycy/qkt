package com.qkt.cli.golden

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Decimal fields in golden records, and journal drop markers inside and outside the capture window. */
class GoldenFieldsAndDropMarkersTest {
    @TempDir
    lateinit var tmp: Path

    private val record = Json.parseToJsonElement("""{"price":"2350.25","bad":"two","none":null}""").jsonObject

    private val from = Instant.parse("2026-06-26T00:00:00Z").toEpochMilli()
    private val to = Instant.parse("2026-06-27T12:00:00Z").toEpochMilli()

    private fun marker(
        day: String,
        text: String,
    ) = Files.writeString(Files.createDirectories(tmp.resolve("journal")).resolve("engine-$day.dropped"), text)

    @Test
    fun `a decimal field reads, an absent one is null or missing, and a malformed one names its place`() {
        assertThat(requireDecimal(record, "price", "engine/a.jsonl", 3L)).isEqualByComparingTo(BigDecimal("2350.25"))
        assertThat(optionalDecimal(record, "none", "engine/a.jsonl", 3L)).isNull()
        assertThat(optionalDecimal(record, "absent", "engine/a.jsonl", 3L)).isNull()
        assertThatThrownBy { requireDecimal(record, "absent", "engine/a.jsonl", 3L) }
            .hasMessage("missing decimal absent at engine/a.jsonl:3")
        assertThatThrownBy { optionalDecimal(record, "bad", "engine/a.jsonl", 3L) }
            .hasMessage("invalid decimal bad at engine/a.jsonl:3")
    }

    @Test
    fun `zero-drop markers in the window, markers outside it and other files pass`() {
        marker("2026-06-26", "0\n")
        marker("2026-06-25", "12")
        marker("2026-06-28", "junk")
        Files.writeString(tmp.resolve("journal/venue-2026-06-26.dropped"), "4")
        Files.writeString(tmp.resolve("journal/notes.txt"), "4")

        assertThatCode { assertNoDrops(tmp, "engine", from, to) }.doesNotThrowAnyException()
        assertThatCode { assertNoDrops(tmp.resolve("absent"), "engine", from, to) }.doesNotThrowAnyException()
    }

    @Test
    fun `a marker in the window with drops or an unreadable count is refused`() {
        marker("2026-06-27", "3")
        assertThatThrownBy { assertNoDrops(tmp, "engine", from, to) }
            .hasMessage("engine journal dropped 3 record(s) on 2026-06-27")

        val bad = marker("2026-06-27", "three")
        assertThatThrownBy { assertNoDrops(tmp, "engine", from, to) }
            .hasMessage("invalid journal drop marker: $bad")
    }
}
