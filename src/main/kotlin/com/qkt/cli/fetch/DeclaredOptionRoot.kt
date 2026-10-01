package com.qkt.cli.fetch

import com.qkt.instrument.OptionRoot
import com.qkt.instrument.OptionRootsFile
import java.nio.file.Files
import java.nio.file.Path

/** [target] as declared under `options:` in the data root's `instruments.yaml`, or null after saying it is not. */
internal fun declaredOptionRoot(
    target: String,
    dataRoot: Path,
): OptionRoot? {
    val instruments = dataRoot.resolve("instruments.yaml")
    val roots = if (Files.exists(instruments)) OptionRootsFile.load(instruments) else emptyList()
    return roots.firstOrNull { it.root == target }
        ?: null.also { System.err.println("qkt: $target is not declared under options: in $instruments") }
}
