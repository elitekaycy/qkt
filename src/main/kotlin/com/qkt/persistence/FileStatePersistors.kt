package com.qkt.persistence

import java.nio.file.Path

/**
 * The on-disk persistor of a daemon or bot session writing under [stateRoot]: with [async], every write on a writer
 * thread ([AsyncStatePersistor]); otherwise every write synchronous but the excursion marks, written behind
 * ([ExcursionWriteBehind]), as ticks drive them.
 */
fun fileStatePersistor(
    stateRoot: Path,
    async: Boolean,
): StatePersistor {
    val file = FileStatePersistor(stateRoot)
    return if (async) AsyncStatePersistor(file) else ExcursionWriteBehind(file)
}
