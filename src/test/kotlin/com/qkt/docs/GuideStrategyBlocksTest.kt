package com.qkt.docs

import com.qkt.docs.ExampleStrategyBlocks.GUIDE_DIRS
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
 * Keeps the tutorials and how-to guides copyable from top to bottom: every complete `STRATEGY` or
 * `PORTFOLIO` file in a ```qkt block there must parse and compile as `qkt parse` would. The go-live
 * pages (#1435) once shipped strategies that failed on their first line of `RULES`.
 */
class GuideStrategyBlocksTest {
    private fun pages(): List<Path> = GUIDE_DIRS.flatMap { examplePages(it) }

    @Test
    fun `the go-live pages are among the guides this test guards`() {
        val guarded = pages().filter { examplesIn(it).isNotEmpty() }.map { it.fileName.toString() }

        assertThat(guarded).contains("deploy-exness.md", "backtest-to-live.md", "first-strategy.md")
    }

    @TestFactory
    fun `every complete strategy in the tutorials and how-to guides parses and compiles`(
        @TempDir tempDir: Path,
    ): List<DynamicTest> =
        pages().flatMap { page ->
            val examples = examplesIn(page)
            examples.map { example ->
                val where = "$page:${example.block.line}"
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
