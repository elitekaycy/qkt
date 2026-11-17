package com.qkt.marketdata.ingest.plan

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Every descriptor key read, defaulted and refused, one arm each. */
class DescriptorYamlFieldsTest {
    private fun decode(yaml: String) = DescriptorYaml.decode(yaml, "p.yaml")

    @Test
    fun `an empty map is a custom descriptor with nothing set`() {
        assertThat(decode("{}")).isEqualTo(ImportDescriptor())
        assertThat(decode("{}").id).isEqualTo("custom")
    }

    @Test
    fun `every key is read into its field`() {
        val d =
            decode(
                """
                id: full
                description: a preset
                kind: bars
                format: json
                delimiter: tab
                decimal: ","
                rows: [data, items]
                where: {complete: true}
                time: [a, b]
                timeFormat: epoch
                clock: UTC
                calendar: nyse
                timeframe: 1m
                label: close
                basis: bid
                fields: {close: c}
                available: avail
                availableLag: 1d
                availableFormat: iso
                digits: 5
                volume: tick count
                carryForward: true
                float32: false
                match: {header: [x, y], columns: 4, firstCell: "^1", filename: ".*csv", delimiter: ";", json: true}
                """.trimIndent(),
            )

        assertThat(d.id).isEqualTo("full")
        assertThat(d.kind).isEqualTo(IngestKind.BARS)
        assertThat(d.json).isTrue()
        assertThat(d.delimiter).isEqualTo('\t')
        assertThat(d.decimal).isEqualTo(',')
        assertThat(d.rows).containsExactly("data", "items")
        assertThat(d.where).containsEntry("complete", "true")
        assertThat(d.time).containsExactly("a", "b")
        assertThat(d.label).isEqualTo(BarLabel.CLOSE)
        assertThat(d.basis).isEqualTo(PriceBasis.BID)
        assertThat(d.fields).containsEntry("close", "c")
        assertThat(d.digits).isEqualTo(5)
        assertThat(d.carryForward).isTrue()
        assertThat(d.float32).isFalse()
        assertThat(d.match).isEqualTo(PresetMatch(listOf("x", "y"), 4, "^1", ".*csv", ';', true))
        assertThat(decode(DescriptorYaml.encode(d))).isEqualTo(d.copy(match = null))
    }

    @Test
    fun `a match block with nothing set has empty conditions and a scalar rows is one key`() {
        val d = decode("rows: data\nformat: csv\nmatch: {}\n")

        assertThat(d.rows).containsExactly("data")
        assertThat(d.json).isFalse()
        assertThat(d.match).isEqualTo(PresetMatch())
        assertThat(decode("match: 5").match).isNull()
    }

    @Test
    fun `bad values are refused naming the preset file`() {
        assertThatThrownBy { decode("- a") }.hasMessage("p.yaml: a preset must be a YAML map")
        assertThatThrownBy { decode("format: xml") }.hasMessage("p.yaml: format must be csv or json")
        assertThatThrownBy { decode("delimiter: ab") }
            .hasMessage("p.yaml: 'ab' must be a single character (or 'tab')")
        assertThatThrownBy { decode("decimal: ab") }.hasMessageContaining("must be a single character")
        assertThatThrownBy { decode("where: [a]") }.hasMessage("p.yaml: 'where' must be a map")
        assertThatThrownBy { decode("fields: x") }.hasMessage("p.yaml: 'fields' must be a map")
        assertThatThrownBy { decode("kind: dogs") }.hasMessageContaining("unknown kind 'dogs'")
        assertThatThrownBy { decode("match: {size: 3}") }.hasMessage("p.yaml: unknown match key(s) size")
        assertThatThrownBy { decode("match: {delimiter: ab}") }.hasMessageContaining("single character")
        assertThatThrownBy { decode("digits: many") }.isInstanceOf(NumberFormatException::class.java)
        assertThatThrownBy { decode("carryForward: yes") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { decode("match: {json: maybe}") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { decode("label: middle") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { decode("basis: avg") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `encoding skips empty lists and maps and writes flags and numbers bare`() {
        val text =
            DescriptorYaml.encode(
                ImportDescriptor(id = "e", digits = 3, carryForward = false, rows = listOf("a")),
            )

        assertThat(text.lines()).contains("digits: 3", "carryForward: false", "rows: [\"a\"]")
        assertThat(text).doesNotContain("where").doesNotContain("time:").doesNotContain("fields")
    }
}
