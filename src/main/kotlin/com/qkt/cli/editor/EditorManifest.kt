package com.qkt.cli.editor

import com.qkt.cli.UserDirs
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persistent record of which editor integrations `qkt editor install` placed
 * on the user's machine, and which exact files were written. `uninstall`
 * consults the manifest so it removes only files this CLI wrote — never the
 * user's own customizations.
 *
 * Stored at `$XDG_CONFIG_HOME/qkt/editor-install.json` (or `~/.config/qkt/`
 * when XDG is unset). Re-installing the same target overwrites its entry, so
 * the manifest stays a clean reflection of current state.
 */
@Serializable
data class EditorManifest(
    val installs: List<Entry> = emptyList(),
) {
    @Serializable
    data class Entry(
        val target: EditorTarget,
        val files: List<String>,
        val installedAt: Long,
    )

    fun recordFor(target: EditorTarget): Entry? = installs.firstOrNull { it.target == target }

    fun withInstall(
        target: EditorTarget,
        files: List<Path>,
        now: Long = System.currentTimeMillis(),
    ): EditorManifest {
        val updated = Entry(target, files.map { it.toString() }, now)
        val others = installs.filterNot { it.target == target }
        return EditorManifest(others + updated)
    }

    fun withoutInstall(target: EditorTarget): EditorManifest =
        EditorManifest(installs.filterNot { it.target == target })

    companion object {
        private val JSON =
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
            }

        fun defaultPath(
            env: Map<String, String> = System.getenv(),
            home: Path = Path.of(System.getProperty("user.home")),
            osName: String = System.getProperty("os.name", "").lowercase(),
        ): Path = UserDirs(osName = osName, env = env, home = home).configHome().resolve("editor-install.json")

        fun load(path: Path): EditorManifest {
            if (!Files.isRegularFile(path)) return EditorManifest()
            return JSON.decodeFromString(serializer(), Files.readString(path))
        }

        fun save(
            path: Path,
            manifest: EditorManifest,
        ) {
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, JSON.encodeToString(serializer(), manifest))
        }
    }
}
