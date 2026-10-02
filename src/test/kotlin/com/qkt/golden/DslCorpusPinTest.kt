package com.qkt.golden

import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Pins the parsed AST of every `.qkt` file in the repository. A strategy's fingerprint is
 * `sha256(ast.toString())`, so any change to an AST data class (even a new field with a default)
 * changes fingerprints of deployed strategies; this test turns that into a failing build.
 */
class DslCorpusPinTest {
    private val roots = listOf("attestation", "examples", "editor", "src/main/resources", "src/test/resources")
    private val pinFile: Path = Paths.get("src/test/resources/golden/dsl-corpus.txt")

    @Test
    fun `every repository strategy parses to its pinned AST`() {
        val actual = corpus().joinToString("\n", postfix = "\n") { (path, hash) -> "$hash  $path" }
        if (System.getenv("QKT_GOLDEN_UPDATE") == "1") {
            Files.writeString(pinFile, actual)
            fail("dsl corpus pins written; rerun without QKT_GOLDEN_UPDATE")
        }
        assertThat(actual).isEqualTo(Files.readString(pinFile))
    }

    private fun corpus(): List<Pair<String, String>> =
        roots
            .map(Paths::get)
            .filter(Files::isDirectory)
            .flatMap { root -> Files.walk(root).use { s -> s.filter { it.extension == "qkt" }.toList() } }
            .map { it.invariantSeparatorsPathString to astHash(Files.readString(it)) }
            .sortedBy { it.first }

    private fun astHash(source: String): String =
        when (val result = Dsl.parseAny(source)) {
            is ParseResult.Failure -> "PARSE_ERROR"
            is ParseResult.Success ->
                sha256(
                    when (val file = result.value) {
                        is ParsedFile.StrategyFile -> file.ast.toString()
                        is ParsedFile.PortfolioFile -> file.ast.toString()
                    },
                )
        }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
