package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path

/**
 * A qkt-owned block inside a user's own config file (`languages.toml`, `init.el`), fenced by
 * marker comments so reinstalling replaces it and uninstalling removes it without touching
 * anything the user wrote around it.
 */
internal class ManagedBlock(
    commentPrefix: String,
    target: EditorTarget,
) {
    private val begin =
        "$commentPrefix >>> qkt (managed by `qkt editor install ${target.cliName}`; edits inside are overwritten)"
    private val end = "$commentPrefix <<< qkt"

    /** Writes [body] into [file] as the block, replacing any earlier one. */
    fun write(
        file: Path,
        body: String,
    ) {
        val existing = if (Files.isRegularFile(file)) Files.readString(file) else ""
        val rest = strip(existing).trimEnd()
        Files.createDirectories(file.toAbsolutePath().parent)
        Files.writeString(file, (if (rest.isEmpty()) "" else "$rest\n\n") + "$begin\n${body.trimEnd()}\n$end\n")
    }

    /** Removes the block from [file], if present. */
    fun remove(file: Path) {
        if (!Files.isRegularFile(file)) return
        val rest = strip(Files.readString(file)).trimEnd()
        Files.writeString(file, if (rest.isEmpty()) "" else "$rest\n")
    }

    /** [text] without the block. */
    fun strip(text: String): String {
        val start = text.indexOf(begin)
        if (start < 0) return text
        val stop = text.indexOf(end, start)
        if (stop < 0) return text
        return text.substring(0, start) + text.substring(stop + end.length).trimStart('\n')
    }
}
