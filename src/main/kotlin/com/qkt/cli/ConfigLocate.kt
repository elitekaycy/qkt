package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where `qkt.config.yaml` is found: explicit flag, env, upward walk, then system locations.
 */
internal object ConfigLocate {
    /**
     * Standard locations qkt commands look for `qkt.config.yaml` when no explicit
     * `--config` is passed. Order is meaningful:
     *  1. `./qkt.config.yaml` — local-dev convenience, matches the historical hard-coded default.
     *  2. The upward walk: [cwd] then each parent (stopping at a `.git` boundary, [home],
     *     or the filesystem root), so any project subdirectory finds the project config.
     *  3. `/etc/qkt/qkt.config.yaml` — the container-standard location (the qkt-prod compose
     *     image mounts the operator's config here).
     *  4. `~/.qkt/qkt.config.yaml` — per-user config for non-container deployments.
     */
    fun defaultSearchPaths(
        userDirs: UserDirs = UserDirs(),
        home: Path = Path.of(System.getProperty("user.home")),
        cwd: Path = Path.of("").toAbsolutePath().normalize(),
    ): List<Path> {
        val paths = mutableListOf(Path.of("./qkt.config.yaml"))
        paths += upwardWalk(cwd, home)
        if (!userDirs.isWindows) paths.add(Path.of("/etc/qkt/qkt.config.yaml"))
        paths.add(userDirs.configHome().resolve("qkt.config.yaml"))
        paths.add(home.resolve(".qkt").resolve("qkt.config.yaml"))
        // Normalize absolute entries only: the leading "./qkt.config.yaml" is historical.
        return paths.map { if (it.isAbsolute) it.normalize() else it }.distinct()
    }

    /** [cwd] then each parent up to a `.git` boundary, [home], or the filesystem root. */
    private fun upwardWalk(
        cwd: Path,
        home: Path,
    ): List<Path> {
        val out = mutableListOf<Path>()
        var dir: Path = cwd.normalize().toAbsolutePath()
        val stop = home.normalize().toAbsolutePath()
        while (true) {
            out.add(dir.resolve("qkt.config.yaml"))
            if (Files.exists(dir.resolve(".git")) || dir == stop) break
            val parent = dir.parent
            if (parent == null || parent == dir) break
            dir = parent
        }
        return out
    }

    /**
     * Return the first existing file from [searchPaths], or null if none exist.
     * One-shot CLI commands that need a real config (brokers, audit-ticks) call this
     * and fail loud on null. The daemon and run commands tolerate a missing config and
     * fall back to defaults via [load].
     */
    fun locate(searchPaths: List<Path> = defaultSearchPaths()): Path? = searchPaths.firstOrNull { Files.exists(it) }

    /** Resolve `--config`, then `QKT_CONFIG`, then the first documented default location. */
    fun resolvePath(
        explicit: String?,
        searchPaths: List<Path> = defaultSearchPaths(),
        environment: Map<String, String> = System.getenv(),
    ): Path =
        explicit?.let(Path::of)
            ?: environment["QKT_CONFIG"]?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: locate(searchPaths)
            ?: Path.of("./qkt.config.yaml")

}
