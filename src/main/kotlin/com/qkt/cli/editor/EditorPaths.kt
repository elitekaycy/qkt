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
    fun launcher(
        command: Path? =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse(null)
                ?.let { Path.of(it) },
        appHome: Path? = appHomeFromJar(),
        windows: Boolean = System.getProperty("os.name", "").lowercase().contains("win"),
    ): Path? {
        if (command?.fileName?.toString()?.startsWith("qkt") == true) return command
        val bin = appHome?.resolve("bin") ?: return null
        return bin.resolve(if (windows) "qkt.bat" else "qkt").takeIf { Files.isRegularFile(it) }
    }

    fun bundledEditorRoot(
        env: Map<String, String> = System.getenv(),
        cwd: Path = Path.of(".").toAbsolutePath().normalize(),
        appHome: Path? = appHomeFromJar(),
    ): Path? {
        env["QKT_HOME"]?.let {
            val p = Path.of(it).resolve("share/editor")
            if (Files.isDirectory(p)) return p
        }
        appHome?.let {
            val p = it.resolve("share/editor")
            if (Files.isDirectory(p)) return p
        }
        val checkout = cwd.resolve("editor")
        if (Files.isDirectory(checkout)) return checkout
        return null
    }

    private fun appHomeFromJar(): Path? =
        EditorPaths::class.java.protectionDomain
            ?.codeSource
            ?.location
            ?.let(::appHomeOf)

    /** The install root for a runtime jar at [location]: the directory above `lib/`, or null elsewhere. */
    internal fun appHomeOf(location: java.net.URL): Path? {
        val jar =
            try {
                Path.of(location.toURI())
            } catch (_: Exception) {
                return null
            }
        val libDir = jar.parent ?: return null
        if (libDir.fileName?.toString() != "lib") return null
        return libDir.parent
    }
}
