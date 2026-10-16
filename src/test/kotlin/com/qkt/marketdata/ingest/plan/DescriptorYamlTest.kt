package com.qkt.marketdata.ingest.plan

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DescriptorYamlTest {
    @Test
    fun `every built-in preset loads and survives a write and read back`() {
        for (preset in PresetLibrary.builtIns) {
            val again = DescriptorYaml.decode(DescriptorYaml.encode(preset), preset.id)

            assertThat(again).describedAs(preset.id).isEqualTo(preset.copy(match = null))
        }
    }

    @Test
    fun `a misspelled key fails instead of meaning nothing`() {
        assertThatThrownBy { DescriptorYaml.decode("id: \"x\"\ntimezone: \"UTC\"\n", "my.yaml") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("timezone")
    }

    @Test
    fun `tab delimiters and tag names round-trip`() {
        val d =
            ImportDescriptor(
                id = "mine",
                delimiter = '\t',
                fields = mapOf("close" to "<CLOSE>"),
                time = listOf("<DATE>", "<TIME>"),
            )

        assertThat(DescriptorYaml.decode(DescriptorYaml.encode(d), "mine")).isEqualTo(d)
    }

    @Test
    fun `flags overlay a preset field by field`() {
        val preset = PresetLibrary.resolve("mt5-bars")
        val merged = preset.overlaidBy(ImportDescriptor(clock = "Europe/Athens", fields = mapOf("volume" to "<VOL>")))

        assertThat(merged.clock).isEqualTo("Europe/Athens")
        assertThat(merged.fields["volume"]).isEqualTo("<VOL>")
        assertThat(merged.fields["close"]).isEqualTo("<CLOSE>")
        assertThat(merged.id).isEqualTo("mt5-bars")
    }

    @Test
    fun `presets that look alike are told apart by what only one of them requires`() {
        val shape = InputShape("DAT_MT_EURUSD_M1_2017.csv", false, ',', null, 7, "2017.01.02")
        val plain = shape.copy(fileName = "EURUSD_M1.csv")

        assertThat(PresetChooser.choose { shape }?.id).isEqualTo("histdata-mt")
        assertThat(PresetChooser.choose { plain }?.id).isEqualTo("mt4-csv")
    }

    @Test
    fun `two presets that fit equally refuse`() {
        val a = ImportDescriptor(id = "a", match = PresetMatch(columns = 3))
        val b = ImportDescriptor(id = "b", match = PresetMatch(columns = 3))

        assertThatThrownBy { PresetChooser.choose(listOf(a, b)) { InputShape("x.csv", false, ',', null, 3, "1") } }
            .hasMessageContaining("a, b")
    }
}
