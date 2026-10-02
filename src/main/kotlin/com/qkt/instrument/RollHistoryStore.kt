package com.qkt.instrument

import java.nio.file.Path

/**
 * Reads and writes roll histories beside the catalogs, under
 * `<dataRoot>/contracts/<VENUE>/<ROOT>.rolls.json`.
 */
class RollHistoryStore(
    dataRoot: Path,
) {
    private val files = RootFileStore(dataRoot, ".rolls.json", RollHistory.serializer(), { it.root }, "roll history")

    /** Where [root]'s roll history lives. */
    fun path(root: String): Path = files.path(root)

    /** [root]'s roll history, or null when none has been built. */
    fun read(root: String): RollHistory? = files.read(root)

    /** Writes [history], replacing any previous file. */
    fun write(history: RollHistory) = files.write(history)
}
