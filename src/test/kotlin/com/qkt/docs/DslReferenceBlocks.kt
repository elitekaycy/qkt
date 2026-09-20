package com.qkt.docs

import java.nio.file.Files
import java.nio.file.Path

/** Reads the ```qkt fenced blocks, and the opt-out marker above each, from the DSL reference pages. */
internal object DslReferenceBlocks {
    data class Block(
        val file: String,
        val line: Int,
        val marker: String?,
        val lines: List<String>,
    )

    fun referenceBlocks(): List<Block> =
        Files
            .list(REFERENCE_DIR)
            .use { files -> files.filter { it.toString().endsWith(".md") }.sorted().toList() }
            .flatMap { path -> blocksIn(path) }

    fun blocksIn(path: Path): List<Block> {
        val lines = Files.readAllLines(path)
        val out = mutableListOf<Block>()
        var i = 0
        while (i < lines.size) {
            if (FENCE.matches(lines[i].trim())) {
                var previous = i - 1
                while (previous >= 0 && lines[previous].isBlank()) previous--
                val marker = if (previous >= 0) MARKER.find(lines[previous])?.groupValues?.get(1) else null
                var end = i + 1
                while (end < lines.size && !lines[end].trim().startsWith("```")) end++
                out += Block(path.fileName.toString(), i + 1, marker, lines.subList(i + 1, end))
                i = end
            }
            i++
        }
        return out
    }

    val REFERENCE_DIR: Path = Path.of("docs", "reference", "dsl")
    val FENCE = Regex("```qkt(\\s.*)?")
    val MARKER = Regex("<!--\\s*qkt-doc:\\s*([a-z-]+)\\b[^>]*-->")
}
