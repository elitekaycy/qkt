package com.qkt.docs

import com.qkt.docs.DslReferenceBlocks.REFERENCE_DIR
import com.qkt.docs.DslReferenceBlocks.referenceBlocks
import com.qkt.docs.DslSnippetCompiler.checkBlock
import java.nio.file.Path
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir

/**
 * Keeps the DSL reference honest: every ```qkt block in `docs/reference/dsl/` must parse and compile
 * exactly as `qkt parse` would (#1127).
 *
 * A block is checked in the shape it is written. A complete `STRATEGY`/`PORTFOLIO` file is compiled as-is.
 * A snippet is wrapped in the smallest strategy that holds it: stream aliases it mentions are declared,
 * `LET` lines go before `RULES`, a bare action gets a `WHEN ... THEN`, an expression becomes a `LET`,
 * and a portfolio snippet gets stub child files. Names that the surrounding prose defines elsewhere
 * (`signal`, `regime_changed`) are declared as a number, a boolean or a string, whichever compiles.
 *
 * An HTML comment on the line above a fence opts a block out:
 * - `<!-- qkt-doc: grammar -->` a syntax sketch with placeholders such as `<expr>`; not parsed.
 * - `<!-- qkt-doc: illegal -->` an example of an error; it must fail to parse.
 * - `<!-- qkt-doc: skip <reason> -->` a real example that cannot be checked yet; the reason names the issue.
 */
class DslReferenceCodeBlocksTest {
    @TestFactory
    fun `every qkt block in the DSL reference parses and compiles`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> {
        val blocks = referenceBlocks()
        assertThat(blocks).withFailMessage("found no ```qkt blocks under $REFERENCE_DIR").isNotEmpty()
        return blocks.map { block ->
            DynamicTest.dynamicTest("${block.file}:${block.line}") {
                when (block.marker) {
                    "grammar", "skip" -> Unit
                    "illegal" -> assertThat(checkBlock(block, tempDir)).isNotNull()
                    else -> {
                        val error =
                            assertTimeoutPreemptively(
                                Duration.ofSeconds(20),
                                ThrowingSupplier { checkBlock(block, tempDir) },
                            )
                        assertThat(error)
                            .withFailMessage(
                                "%s:%d does not compile: %s%n%s",
                                block.file,
                                block.line,
                                error,
                                block.lines.joinToString("\n"),
                            ).isNull()
                    }
                }
            }
        }
    }
}
