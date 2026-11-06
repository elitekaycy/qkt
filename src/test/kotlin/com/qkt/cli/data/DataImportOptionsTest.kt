package com.qkt.cli.data

import com.qkt.cli.Args
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DataImportOptionsTest {
    private fun request(vararg flags: String) =
        DataImportOptions.request(
            Args(arrayOf("data", "import", "ticks.csv", "--as", "EXNESS:XAUUSD", *flags)),
            listOf(Path.of("ticks.csv")),
            Path.of("data"),
        )

    private fun refused(
        message: String,
        vararg flags: String,
    ) = assertThatThrownBy { request(*flags) }.isInstanceOf(IllegalArgumentException::class.java).hasMessage(message)

    @Test
    fun `digits format separators and the where filter reach the descriptor`() {
        val overrides =
            request(
                "--digits",
                "3",
                "--format",
                "JSON",
                "--delimiter",
                "tab",
                "--decimal",
                ",",
                "--where",
                "sym=XAU",
            ).overrides

        assertThat(overrides.digits).isEqualTo(3)
        assertThat(overrides.json).isTrue()
        assertThat(overrides.delimiter).isEqualTo('\t')
        assertThat(overrides.decimal).isEqualTo(',')
        assertThat(overrides.where).containsEntry("sym", "XAU")
        assertThat(request("--format", "csv", "--delimiter", "\\t").overrides.let { it.json to it.delimiter })
            .isEqualTo(false to '\t')
    }

    @Test
    fun `conflicts keep the last value only when asked and latin-1 names its charset`() {
        assertThat(request("--on-conflict", "last").keepLastOnConflict).isTrue()
        assertThat(request("--on-conflict", "refuse").keepLastOnConflict).isFalse()
        assertThat(request().keepLastOnConflict).isFalse()
        assertThat(request("--encoding", "latin-1").charset?.name()).isEqualTo("ISO-8859-1")
        assertThat(request("--encoding", "UTF-16").charset?.name()).isEqualTo("UTF-16")
    }

    @Test
    fun `malformed flags are refused naming the flag`() {
        refused("--digits must be a whole number: three", "--digits", "three")
        refused("--on-conflict must be refuse or last: first", "--on-conflict", "first")
        refused("--format must be csv or json: xml", "--format", "xml")
        refused("expected one character (or 'tab'): ';;'", "--delimiter", ";;")
        refused("--map takes name=value, got 'time'", "--map", "time")
        refused("--where takes name=value, got '=XAU'", "--where", "=XAU")
    }
}
