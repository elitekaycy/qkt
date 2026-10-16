package com.qkt.cli

import com.qkt.marketdata.store.DataRoot
import java.nio.file.Files
import java.nio.file.Path
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/** The data root a command uses and why, e.g. `/srv/proj/data` from `data_root in /srv/proj/qkt.config.yaml`. */
internal data class ResolvedDataRoot(
    val path: Path,
    val origin: String,
)

/**
 * The one place a CLI command picks its data root, in this order:
 *  1. `--data-root <dir>`;
 *  2. the `QKT_DATA_HOME` environment variable;
 *  3. `data_root:` in the command's `qkt.config.yaml` (`--config`, then `QKT_CONFIG`, then the usual search), only when
 *     the key is written there; a relative path is resolved against the config file's folder, so `data_root: ./data`
 *     means the project's own folder wherever the command runs;
 *  4. `~/.qkt/data`.
 *
 * An absolute `data_root` that does not exist is refused rather than used or skipped: it is almost always the
 * container path from a Docker setup (`/data`) read on the host, and reading or writing any other folder instead
 * would quietly put data in the wrong place.
 */
internal object CliDataRoot {
    fun resolve(
        args: Args,
        environment: Map<String, String> = System.getenv(),
        configPath: Path? = null,
    ): ResolvedDataRoot {
        args.option("data-root")?.let { return ResolvedDataRoot(Path.of(it), "--data-root") }
        environment[DataRoot.ENV]?.takeIf { it.isNotBlank() }?.let {
            return ResolvedDataRoot(
                Path.of(it),
                DataRoot.ENV,
            )
        }
        val config = configPath ?: ConfigLocate.resolvePath(args.option("config"), environment = environment)
        configured(config)?.let { return it }
        return ResolvedDataRoot(DataRoot.resolveExplicit(null), "default")
    }

    /** [resolve]'s path, after saying where it came from on stderr unless the user typed it. */
    fun of(args: Args): Path {
        val root = resolve(args)
        if (root.origin != "--data-root") System.err.println("qkt: data root ${root.path} (${describe(root)})")
        return root.path
    }

    fun describe(root: ResolvedDataRoot): String =
        when (root.origin) {
            "default" -> "default; move it with --data-root or ${DataRoot.ENV}"
            DataRoot.ENV -> "from ${DataRoot.ENV}"
            else -> "from ${root.origin}"
        }

    private fun configured(config: Path): ResolvedDataRoot? {
        if (!Files.isRegularFile(config)) return null
        val map = Load(LoadSettings.builder().build()).loadFromString(Config.expandVars(Files.readString(config)))
        val raw =
            (map as? Map<*, *>)
                ?.get("data_root")
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return null
        val declared = Path.of(raw)
        val origin = "data_root in ${config.toAbsolutePath().normalize()}"
        if (declared.isAbsolute) {
            if (!Files.isDirectory(declared)) {
                throw ArgError(
                    "$origin is $raw, which does not exist here. qkt project templates use /data as the path inside " +
                        "their Docker container: run the command in the container, set data_root: ./data, or pass --data-root",
                )
            }
            return ResolvedDataRoot(declared, origin)
        }
        val base = config.toAbsolutePath().normalize().parent ?: Path.of("").toAbsolutePath()
        return ResolvedDataRoot(base.resolve(declared).normalize(), origin)
    }
}
