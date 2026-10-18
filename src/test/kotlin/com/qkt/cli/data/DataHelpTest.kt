package com.qkt.cli.data

import com.qkt.cli.CliOptionSchemas
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `qkt data <action> --help` documents each action, and no flag qkt data accepts goes undocumented. */
class DataHelpTest {
    private val actions = listOf("import", "verify", "bars", "compare", "build-bars", "convert", "snapshot", "presets")

    @Test
    fun `every action has its own usage`() {
        for (action in actions) assertThat(DataHelp.of(action)).contains("Usage: qkt data $action")
        assertThat(DataHelp.of(null)).contains("Usage: qkt data <action>").contains("build-bars")
    }

    @Test
    fun `every flag qkt data accepts is described somewhere`() {
        val schema = CliOptionSchemas.forSubcommand("data")!!
        val text = actions.joinToString("\n") { DataHelp.of(it) }
        val missing = (schema.values + schema.flags).filter { "--$it" !in text }

        assertThat(missing).isEmpty()
    }
}
