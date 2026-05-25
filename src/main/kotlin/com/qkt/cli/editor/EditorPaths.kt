package com.qkt.cli.editor

import java.nio.file.Files
import java.nio.file.Path

/**
 * Locate the editor source bundle (`share/editor/`) packaged with the qkt
 * distribution.
 *
 * Search order:
 * 1. `$QKT_HOME/share/editor` if `QKT_HOME` is set.
 * 2. `<jar-dir>/../share/editor` when running from a packaged distribution
 *    (the gradle `application` plugin layout puts the runtime jar in `lib/`,
 *    so the install root is one directory up).
 * 3. `<cwd>/editor` when running from a source checkout — convenient for
 *    developing the installer itself.
 *
 * Returns `null` if no candidate exists.
 */
object EditorPaths {
    fun bundledEditorRoot(
        env: Map<String, String> = System.getenv(),
        cwd: Path = Path.of(".").toAbsolutePath().normalize(),
    ): Path? {
        env["QKT_HOME"]?.let {
            val p = Path.of(it).resolve("share/editor")
            if (Files.isDirectory(p)) return p
        }
        appHomeFromJar()?.let {
            val p = it.resolve("share/editor")
            if (Files.isDirectory(p)) return p
        }
        val checkout = cwd.resolve("editor")
        if (Files.isDirectory(checkout)) return checkout
        return null
    }

    private fun appHomeFromJar(): Path? {
        val cs = EditorPaths::class.java.protectionDomain?.codeSource ?: return null
        val url = cs.location ?: return null
        val jar =
            try {
                Path.of(url.toURI())
            } catch (_: Exception) {
                return null
            }
        val libDir = jar.parent ?: return null
        if (libDir.fileName?.toString() != "lib") return null
        return libDir.parent
    }
}
