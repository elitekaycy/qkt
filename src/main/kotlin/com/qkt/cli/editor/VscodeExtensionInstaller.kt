package com.qkt.cli.editor

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private const val VSCODE_EXTENSION_ID = "elitekaycy.qkt"

/**
 * Installs and removes the VSCode extension through the `code` CLI, using the bundled `.vsix` or
 * one built from source with `npx @vscode/vsce`. VSCode owns the installed files, so the manifest
 * records the install without any placed paths.
 */
internal class VscodeExtensionInstaller(
    private val detector: EditorDetector,
    private val manifestPath: Path,
    private val out: Appendable,
    private val err: Appendable,
    private val processRunner: (List<String>, Path?) -> Int,
    private val pathLookup: (String) -> Path?,
) {
    /** Uninstalls the extension and drops its manifest record. Returns true on success. */
    fun uninstall(): Boolean {
        val cli =
            detector.vscodeCli() ?: run {
                err.appendLine("qkt: vscode 'code' command not on PATH — cannot uninstall the extension.")
                return false
            }
        val rc = processRunner(listOf(cli.toString(), "--uninstall-extension", VSCODE_EXTENSION_ID), null)
        if (rc != 0) {
            err.appendLine("qkt: vscode uninstall failed (exit $rc)")
            return false
        }
        val manifest = EditorManifest.load(manifestPath).withoutInstall(EditorTarget.VSCODE)
        EditorManifest.save(manifestPath, manifest)
        out.appendLine("qkt editor: removed VSCode extension $VSCODE_EXTENSION_ID")
        return true
    }

    /** Installs the extension from the editor root. Null after printing why it could not. */
    fun install(root: Path): EditorInstaller.InstallResult? {
        val cli =
            detector.vscodeCli() ?: run {
                err.appendLine("qkt: vscode 'code' command not on PATH — install VSCode first, then re-run.")
                return null
            }
        val vscodeRoot = root.resolve("vscode")
        if (!Files.isDirectory(vscodeRoot)) {
            err.appendLine("qkt: editor source for VSCode not found at $vscodeRoot")
            return null
        }
        val vsix =
            findExistingVsix(vscodeRoot)
                ?: buildVsixFromSource(vscodeRoot)
                ?: run {
                    err.appendLine(
                        "qkt: no .vsix bundled and could not build one (vsce/npx not on PATH).",
                    )
                    err.appendLine(
                        "     Download the latest .vsix from: https://github.com/elitekaycy/qkt/releases/latest",
                    )
                    return null
                }
        val rc = processRunner(listOf(cli.toString(), "--install-extension", vsix.toString()), null)
        if (rc != 0) {
            err.appendLine("qkt: vscode install failed (exit $rc)")
            return null
        }
        out.appendLine("qkt editor: installed VSCode extension from $vsix")
        // VSCode owns the installed extension under its own extensions dir;
        // nothing for the uninstall manifest to track at filesystem level.
        return EditorInstaller.InstallResult(EditorTarget.VSCODE, emptyList())
    }

    private fun findExistingVsix(vscodeRoot: Path): Path? =
        Files.list(vscodeRoot).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".vsix") }
                .findFirst()
                .orElse(null)
        }

    private fun buildVsixFromSource(vscodeRoot: Path): Path? {
        val npx = pathLookup("npx") ?: return null
        val rc =
            processRunner(
                listOf(
                    npx.toString(),
                    "--yes",
                    "@vscode/vsce@latest",
                    "package",
                    "--no-dependencies",
                ),
                vscodeRoot,
            )
        if (rc != 0) return null
        return findExistingVsix(vscodeRoot)
    }
}

/** Runs [cmd] with inherited stdio, returning its exit code, or -1 when it cannot start. */
internal fun runProcess(
    cmd: List<String>,
    workingDir: Path?,
): Int =
    try {
        val pb = ProcessBuilder(cmd).inheritIO()
        if (workingDir != null) pb.directory(workingDir.toFile())
        pb.start().waitFor()
    } catch (_: IOException) {
        -1
    }

/** Finds an executable named [name] on `$PATH`. */
internal fun defaultPathLookup(name: String): Path? {
    val path = System.getenv("PATH") ?: return null
    val sep = System.getProperty("path.separator") ?: ":"
    for (dir in path.split(sep).filter { it.isNotBlank() }) {
        val c = Path.of(dir).resolve(name)
        if (Files.isExecutable(c)) return c
    }
    return null
}
