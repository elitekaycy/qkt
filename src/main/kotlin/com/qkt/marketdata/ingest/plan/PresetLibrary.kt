package com.qkt.marketdata.ingest.plan

import java.nio.file.Files
import java.nio.file.Path

/**
 * The built-in source presets shipped with qkt (one YAML file each under
 * `com/qkt/marketdata/ingest/presets/`, listed in its `index.txt`), and loading of a user's own preset
 * file. A preset is data, never code: adding a source is adding a file.
 */
object PresetLibrary {
    private const val DIR = "/com/qkt/marketdata/ingest/presets/"

    /** Every built-in preset, in index order. */
    val builtIns: List<ImportDescriptor> by lazy {
        val index =
            resource("index.txt")
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith('#') }
        index.map { id ->
            val preset = DescriptorYaml.decode(resource("$id.yaml"), "preset $id")
            require(preset.id == id) { "preset $id has id ${preset.id}" }
            preset
        }
    }

    /** The preset [nameOrPath] names: a built-in id, or a YAML file on disk. */
    fun resolve(nameOrPath: String): ImportDescriptor {
        builtIns.firstOrNull { it.id == nameOrPath }?.let { return it }
        val path = Path.of(nameOrPath)
        require(Files.isRegularFile(path)) {
            "no preset '$nameOrPath' (built-in: ${builtIns.joinToString { it.id }}; or give a .yaml file)"
        }
        return DescriptorYaml.decode(Files.readString(path), path.toString())
    }

    private fun resource(name: String): String =
        PresetLibrary::class.java
            .getResourceAsStream(DIR + name)
            ?.use { String(it.readAllBytes(), Charsets.UTF_8) }
            ?: error("missing preset resource $DIR$name")
}
