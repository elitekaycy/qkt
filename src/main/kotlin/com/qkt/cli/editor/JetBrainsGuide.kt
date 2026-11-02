package com.qkt.cli.editor

import java.nio.file.Path

/**
 * JetBrains IDEs register TextMate bundles and language servers only through their settings
 * UI, so `qkt editor install intellij` prints the two steps with the bundled paths filled in,
 * and records nothing. The bundle is the VS Code extension folder, which IntelliJ's built-in
 * TextMate support reads directly.
 */
internal object JetBrainsGuide {
    fun print(
        root: Path,
        out: Appendable,
    ): EditorInstaller.InstallResult {
        val bundle = root.resolve("vscode").toAbsolutePath().normalize()
        out.appendLine("qkt editor: JetBrains IDEs (IntelliJ IDEA, PyCharm, WebStorm, ...) need two settings steps:")
        out.appendLine("     1. Highlighting: Settings > Editor > TextMate Bundles > + and choose $bundle")
        out.appendLine("     2. Completion and diagnostics: install the LSP4IJ plugin, then Languages & Frameworks >")
        out.appendLine("        Language Servers > + with command: qkt lsp, and a file name pattern mapping *.qkt")
        return EditorInstaller.InstallResult(EditorTarget.INTELLIJ, emptyList())
    }
}
