package com.qkt.cli.editor

import java.nio.file.Path

/**
 * Zed installs extensions only from inside the editor, so `qkt editor install zed` prints the
 * steps instead of placing files, and records nothing in the manifest.
 */
internal object ZedGuide {
    fun print(
        root: Path,
        out: Appendable,
    ): EditorInstaller.InstallResult {
        val dir = root.resolve("zed").toAbsolutePath().normalize()
        out.appendLine("qkt editor: Zed installs extensions from inside the editor:")
        out.appendLine("     1. Open Zed's command palette and run: zed: extensions")
        out.appendLine("     2. Search for qkt and click Install.")
        out.appendLine("     Not listed yet? Run zed: install dev extension and choose $dir")
        out.appendLine("     Completion and diagnostics need qkt on the PATH Zed sees.")
        return EditorInstaller.InstallResult(EditorTarget.ZED, emptyList())
    }
}
