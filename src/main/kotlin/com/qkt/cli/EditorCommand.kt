package com.qkt.cli

import com.qkt.cli.editor.EditorDetector
import com.qkt.cli.editor.EditorPaths
import com.qkt.cli.editor.EditorTarget

/**
 * `qkt editor list | install <target> | uninstall <target>`
 *
 * Manages the qkt editor integrations (syntax highlighting, snippets) bundled
 * with the qkt distribution under `share/editor/`. Currently supports VSCode,
 * Neovim, Vim, and Sublime Text.
 */
class EditorCommand(
    private val args: Args,
) {
    fun run(): Int =
        when (val sub = args.firstNonOption()) {
            null, "list" -> list()
            "install" -> install()
            "uninstall" -> uninstall()
            else -> {
                System.err.println("qkt: unknown editor subcommand '$sub' (expected: list, install, uninstall)")
                ExitCodes.ARG_ERROR
            }
        }

    private fun list(): Int {
        val detector = EditorDetector()
        val bundle = EditorPaths.bundledEditorRoot()
        println("qkt editor — bundled at: ${bundle ?: "<not found>"}")
        println()
        println("Supported targets:")
        for ((target, detected) in detector.all()) {
            val tag = if (detected) "detected" else "not found"
            println("  %-10s %-14s [$tag]".format(target.cliName, target.displayName))
        }
        return ExitCodes.SUCCESS
    }

    private fun install(): Int {
        System.err.println("qkt editor install: not implemented yet")
        return ExitCodes.USER_ERROR
    }

    private fun uninstall(): Int {
        System.err.println("qkt editor uninstall: not implemented yet")
        return ExitCodes.USER_ERROR
    }
}
