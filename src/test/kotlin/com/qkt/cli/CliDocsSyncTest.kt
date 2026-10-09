package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the #1377 docs drift fix in: every validated subcommand must be mentioned in the CLI
 * reference (or its dedicated page), so `qkt help` and the docs cannot silently diverge again.
 */
class CliDocsSyncTest {
    private fun docs(vararg names: String): String =
        names.map { Path.of("docs/reference/$it") }
            .filter { Files.exists(it) }
            .joinToString("\n") { Files.readString(it) }

    @Test
    fun `every subcommand appears in the cli reference`() {
        val reference = docs("cli-commands.md", "bot-cli.md")
        val missing =
            CliOptionSchemas.names()
                .filter { !it.startsWith("-") && it != "help" }
                .filter { name -> !reference.contains("qkt $name") }
        assertThat(missing).isEmpty()
    }
}
