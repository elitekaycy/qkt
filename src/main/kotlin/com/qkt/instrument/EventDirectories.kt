package com.qkt.instrument

/**
 * Several [EventDirectory]s read as one: a family is answered by the first of [directories] that declares it (a
 * session's catalogued families, then each venue's live listing), and an undeclared one lists nothing.
 */
internal class EventDirectories(
    private val directories: List<EventDirectory>,
) : EventDirectory {
    override fun family(root: String): EventFamily? = directories.firstNotNullOfOrNull { it.family(root) }

    override fun contracts(root: String): List<String> = owner(root)?.contracts(root).orEmpty()

    override fun aliveAt(
        root: String,
        atMs: Long,
    ): List<String> = owner(root)?.aliveAt(root, atMs).orEmpty()

    private fun owner(root: String): EventDirectory? = directories.firstOrNull { it.family(root) != null }
}
