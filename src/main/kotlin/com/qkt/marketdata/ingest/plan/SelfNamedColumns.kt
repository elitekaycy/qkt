package com.qkt.marketdata.ingest.plan

/**
 * Maps the columns a file names after qkt's own fields, for an import that matched no preset but states
 * its kind: a column called exactly `open`, `bid`, `volume` (any case) fills that field, and the one
 * column called `time`, `timestamp`, `datetime` or `date` is the time. Fields and time already mapped are
 * kept, and a file with two time-like columns (a split date and time) still needs `--map time=`.
 */
object SelfNamedColumns {
    private val TIME_NAMES = listOf("time", "timestamp", "datetime", "date")

    /** [descriptor] with the self-named columns among [names] mapped. */
    fun fill(
        descriptor: ImportDescriptor,
        names: List<String>?,
    ): ImportDescriptor {
        val kind = descriptor.kind ?: return descriptor
        val columns = names?.map { it.trim() } ?: return descriptor
        val found = { name: String -> columns.firstOrNull { it.equals(name, ignoreCase = true) } }
        val fields =
            kind.roles
                .filter { it !in descriptor.fields }
                .mapNotNull { role -> found(role)?.let { role to it } }
                .toMap()
        val time = descriptor.time.ifEmpty { TIME_NAMES.mapNotNull(found).takeIf { it.size == 1 }.orEmpty() }
        return descriptor.copy(fields = descriptor.fields + fields, time = time)
    }
}
