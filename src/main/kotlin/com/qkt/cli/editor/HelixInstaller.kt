package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Installs qkt into Helix: a managed block in `languages.toml` that binds `*.qkt`, starts
 * `qkt lsp` in a small JVM and points the grammar at the bundled tree-sitter source; the
 * highlight queries under `runtime/queries/qkt/`; and, when a C compiler is on `PATH`, the
 * compiled grammar under `runtime/grammars/`. Without a compiler, `hx --grammar build` builds
 * it from the same bundled source.
 */
internal class HelixInstaller(
    private val configDir: Path,
    private val out: Appendable,
    private val processRunner: (List<String>, Path?) -> Int,
    private val pathLookup: (String) -> Path?,
    private val windows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows"),
) {
    /** Installs from the editor root. Null after printing why it could not. */
    fun install(
        root: Path,
        err: Appendable,
    ): EditorInstaller.InstallResult? {
        val grammarDir = root.resolve("tree-sitter-qkt").toAbsolutePath().normalize()
        val queries = grammarDir.resolve("queries/highlights.scm")
        if (!Files.isRegularFile(queries)) {
            err.appendLine("qkt: tree-sitter grammar for Helix not found at $grammarDir")
            return null
        }
        val placed = mutableListOf<Path>()
        val queriesDst = configDir.resolve("runtime/queries/qkt/highlights.scm")
        Files.createDirectories(queriesDst.parent)
        Files.copy(queries, queriesDst, StandardCopyOption.REPLACE_EXISTING)
        placed.add(queriesDst)

        block.write(configDir.resolve("languages.toml"), body(grammarDir))

        val compiled = compileGrammar(grammarDir)
        if (compiled != null) {
            placed.add(compiled)
            out.appendLine("qkt editor: installed Helix support (languages.toml, queries, grammar at $compiled)")
        } else {
            out.appendLine("qkt editor: installed Helix support (languages.toml, queries at $configDir)")
            out.appendLine("     No C compiler on PATH to build the grammar. Run: hx --grammar build")
        }
        return EditorInstaller.InstallResult(EditorTarget.HELIX, placed)
    }

    /** Removes the managed block from `languages.toml`, leaving the rest of the file as it was. */
    fun removeConfig() = block.remove(configDir.resolve("languages.toml"))

    private val block = ManagedBlock("#", EditorTarget.HELIX)

    private fun compileGrammar(grammarDir: Path): Path? {
        val cc = sequenceOf("cc", "gcc", "clang").firstNotNullOfOrNull { pathLookup(it) } ?: return null
        val src = grammarDir.resolve("src")
        val dst = configDir.resolve("runtime/grammars/qkt." + if (windows) "dll" else "so")
        Files.createDirectories(dst.parent)
        val cmd =
            listOf(
                cc.toString(),
                "-shared",
                "-fPIC",
                "-O2",
                "-I",
                src.toString(),
                src.resolve("parser.c").toString(),
                "-o",
                dst.toString(),
            )
        return if (processRunner(cmd, null) == 0 && Files.isRegularFile(dst)) dst else null
    }

    private fun body(grammarDir: Path): String {
        val path = grammarDir.toString().replace("\\", "/")
        return """
            |[[language]]
            |name = "qkt"
            |scope = "source.qkt"
            |file-types = ["qkt"]
            |comment-tokens = ["--", "#"]
            |block-comment-tokens = { start = "/*", end = "*/" }
            |indent = { tab-width = 4, unit = "    " }
            |language-servers = ["qkt"]
            |grammar = "qkt"
            |
            |[language-server.qkt]
            |command = "qkt"
            |args = ["lsp"]
            |environment = { JAVA_TOOL_OPTIONS = "$LEAN_JVM_OPTIONS" }
            |
            |[[grammar]]
            |name = "qkt"
            |source = { path = "$path" }
            """.trimMargin()
    }
}

/** JVM options every editor integration starts `qkt lsp` with: capped heap, one GC and one JIT thread. */
internal const val LEAN_JVM_OPTIONS =
    "-Xms16m -Xmx256m -Xss512k -XX:TieredStopAtLevel=1 -XX:CICompilerCount=1 -XX:ParallelGCThreads=1"
