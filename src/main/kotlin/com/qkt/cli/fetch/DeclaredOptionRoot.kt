package com.qkt.cli.fetch

import com.qkt.instrument.OptionCatalog
import com.qkt.instrument.OptionCatalogStore
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

/** [target]'s declared root and stored catalog, or null after saying which is missing. */
internal fun declaredOptionChain(
    target: String,
    dataRoot: Path,
): Pair<OptionRoot, OptionCatalog>? {
    val root = declaredOptionRoot(target, dataRoot) ?: return null
    val catalog =
        OptionCatalogStore(dataRoot).read(target)
            ?: return null.also {
                System.err.println(
                    "qkt: no option catalog for $target; run: qkt fetch $target --catalog",
                )
            }
    return root to catalog
}
