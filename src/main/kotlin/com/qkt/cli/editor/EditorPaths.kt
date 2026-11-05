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
    /** The file beside the editor manifest that names the qkt launcher for editor plugins. */
    const val RECORDED_LAUNCHER = "qkt-path"

    /**
     * The command that started this qkt: `qkt.exe` itself in the Windows build, otherwise the
     * script in `bin/`, since the running process is then `java`.
     */
    fun launcher(): Path? {
        val command =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse(null)
                ?.let { Path.of(it) }
        if (command?.fileName?.toString()?.startsWith("qkt") == true) return command
        val bin = appHomeFromJar()?.resolve("bin") ?: return null
        val windows = System.getProperty("os.name", "").lowercase().contains("win")
        return bin.resolve(if (windows) "qkt.bat" else "qkt").takeIf { Files.isRegularFile(it) }
    }

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
