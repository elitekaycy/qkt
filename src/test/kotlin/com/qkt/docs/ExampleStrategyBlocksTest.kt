package com.qkt.docs

import com.qkt.docs.ExampleStrategyBlocks.EXAMPLES_DIR
import com.qkt.docs.ExampleStrategyBlocks.check
import com.qkt.docs.ExampleStrategyBlocks.examplePages
import com.qkt.docs.ExampleStrategyBlocks.examplesIn
import java.nio.file.Path
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir

/**
 * Keeps the example pages copyable: every complete `STRATEGY` or `PORTFOLIO` file in a ```qkt block
 * under `docs/examples/` must parse and compile exactly as `qkt parse` would. Fragments on those
 * pages are not checked here; the DSL reference test covers the constructs they use.
 */
class ExampleStrategyBlocksTest {
    @Test
    fun `the example pages hold the complete strategies this test guards`() {
        val examples = examplePages().flatMap { examplesIn(it) }

        assertThat(
            examples,
        ).withFailMessage("found too few complete strategies under $EXAMPLES_DIR").hasSizeGreaterThanOrEqualTo(15)
        assertThat(examples.filter { it.isPortfolio }).isNotEmpty()
    }

    @TestFactory
    fun `every complete strategy in the example pages parses and compiles`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> =
        examplePages().flatMap { page ->
            val examples = examplesIn(page)
            examples.map { example ->
                val where = "${EXAMPLES_DIR.relativize(page)}:${example.block.line}"
                DynamicTest.dynamicTest(where) {
                    val error =
                        assertTimeoutPreemptively(
                            Duration.ofSeconds(20),
                            ThrowingSupplier { check(example, examples, tempDir) },
                        )
                    assertThat(error)
                        .withFailMessage("%s does not compile: %s%n%s", where, error, example.source)
                        .isNull()
                }
            }
        }
}
