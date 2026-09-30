package com.qkt.cli

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class YamlSectionsTest {
    private val text =
        """
        # header comment
        instruments:
          - qktSymbol: A
        # between sections
        futures:
        - { root: CME:ES }
          # nested comment
        other: 1
        """.trimIndent() + "\n"

    @Test
    fun `every section but the named one is kept verbatim`() {
        assertThat(YamlSections.except(text, "instruments"))
            .isEqualTo("futures:\n- { root: CME:ES }\n  # nested comment\nother: 1\n")
    }

    @Test
    fun `a file without other sections keeps nothing`() {
        assertThat(YamlSections.except("instruments:\n  - x\n", "instruments")).isEqualTo("")
    }

    @Test
    fun `extracting twice gives the same text`() {
        val once = YamlSections.except(text, "instruments")
        assertThat(YamlSections.except(once, "instruments")).isEqualTo(once)
    }
}
