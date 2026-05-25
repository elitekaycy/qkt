package com.qkt.cli

import com.qkt.cli.editor.EditorDetector
import com.qkt.cli.editor.EditorInstaller
import com.qkt.cli.editor.EditorPaths
import com.qkt.cli.editor.EditorTarget
import com.qkt.cli.editor.PluginManagerGuard

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
        val arg =
            args.positional(1) ?: run {
                System.err.println("qkt: missing target. Try: qkt editor install <vscode|nvim|vim|sublime|all>")
                return ExitCodes.ARG_ERROR
            }
        val detector = EditorDetector()
        val installer = EditorInstaller(detector = detector)
        val guard = PluginManagerGuard(nvimDir = detector.nvimConfigDir(), vimDir = detector.vimConfigDir())
        val assumeYes = args.flag("yes") || args.flag("y")
        val targets =
            resolveTargets(arg, detector) ?: return ExitCodes.ARG_ERROR
        var failures = 0
        for (t in targets) {
            if (!shouldProceedWithGuard(t, guard, assumeYes)) continue
            if (installer.install(t) == null) failures++
        }
        return if (failures == 0) ExitCodes.SUCCESS else ExitCodes.USER_ERROR
    }

    private fun resolveTargets(
        arg: String,
        detector: EditorDetector,
    ): List<EditorTarget>? {
        if (arg.equals("all", ignoreCase = true)) {
            val detected = detector.all().filterValues { it }.keys.toList()
            if (detected.isEmpty()) {
                System.err.println("qkt: no supported editor detected on this machine")
                return emptyList()
            }
            println("qkt editor: installing for detected editors: ${detected.joinToString(", ") { it.cliName }}")
            return detected
        }
        val parsed =
            EditorTarget.parse(arg) ?: run {
                System.err.println("qkt: unknown target '$arg' (expected: vscode, nvim, vim, sublime, all)")
                return null
            }
        return listOf(parsed)
    }

    private fun shouldProceedWithGuard(
        target: EditorTarget,
        guard: PluginManagerGuard,
        assumeYes: Boolean,
    ): Boolean {
        if (target != EditorTarget.NVIM && target != EditorTarget.VIM) return true
        val managers = guard.detect(target)
        if (managers.isEmpty()) return true
        println()
        println("qkt: detected plugin manager(s) in your ${target.displayName} config: ${managers.joinToString(", ") { it.displayName }}")
        println("     A sideloaded install bypasses your plugin manager — recommended snippets:")
        for (m in managers) printSnippet(m)
        if (assumeYes) {
            println("     --yes given: proceeding with sideload.")
            return true
        }
        val interactive = System.console() != null
        if (!interactive) {
            println("     Non-interactive shell and --yes not given — skipping ${target.displayName}.")
            return false
        }
        print("     Continue with sideload anyway? [y/N] ")
        val answer = readlnOrNull()?.trim()?.lowercase().orEmpty()
        if (answer == "y" || answer == "yes") return true
        println("     Skipping ${target.displayName}.")
        return false
    }

    private fun printSnippet(m: PluginManagerGuard.Manager) {
        when (m) {
            PluginManagerGuard.Manager.LAZY ->
                println(
                    """       lazy.nvim:
       { "elitekaycy/qkt", ft = "qkt",
         config = function(p) vim.opt.rtp:append(p.dir .. "/editor/nvim") end }""",
                )
            PluginManagerGuard.Manager.PACKER ->
                println(
                    """       packer.nvim:
       use { "elitekaycy/qkt", ft = "qkt", rtp = "editor/nvim" }""",
                )
            PluginManagerGuard.Manager.VIM_PLUG ->
                println(
                    """       vim-plug:
       Plug 'elitekaycy/qkt', { 'rtp': 'editor/nvim', 'for': 'qkt' }""",
                )
        }
    }

    private fun uninstall(): Int {
        val arg =
            args.positional(1) ?: run {
                System.err.println("qkt: missing target. Try: qkt editor uninstall <vscode|nvim|vim|sublime|all>")
                return ExitCodes.ARG_ERROR
            }
        val installer = EditorInstaller()
        val targets: List<EditorTarget> =
            if (arg.equals("all", ignoreCase = true)) {
                EditorTarget.entries
            } else {
                val parsed =
                    EditorTarget.parse(arg) ?: run {
                        System.err.println("qkt: unknown target '$arg' (expected: vscode, nvim, vim, sublime, all)")
                        return ExitCodes.ARG_ERROR
                    }
                listOf(parsed)
            }
        var failures = 0
        for (t in targets) {
            if (!installer.uninstall(t)) failures++
        }
        return if (failures == 0) ExitCodes.SUCCESS else ExitCodes.USER_ERROR
    }
}
