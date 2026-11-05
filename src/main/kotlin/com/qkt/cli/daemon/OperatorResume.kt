package com.qkt.cli.daemon

import com.qkt.broker.StreamStopRelease

/**
 * What `qkt resume` acts on (#1640). A portfolio name clears the book's own halt and the halts that book put on its
 * children. A child name, written `book/alias` or `book:alias`, resumes that child; while its book is halted it is
 * refused, since the book's halt holds every child. Every other name is a standalone strategy, resumed as before.
 */
internal class OperatorResume(
    private val registry: StrategyRegistry,
) {
    /** Resumes [target], clearing the stream stops that are safe to clear on each strategy it resumes. */
    fun resume(target: Target): ControlResult =
        when (target) {
            Target.All -> resumeAll()
            is Target.Strategy -> resumeOne(target.name)
        }

    private fun resumeAll(): ControlResult {
        val books = registry.listPortfolios()
        val cleared = books.mapNotNull { book -> book.supervisor.resumeBook()?.let { book.name to it.reason } }
        val stops = mutableListOf<StreamStopRelease>()
        val strategies = registry.list()
        strategies.forEach { resumeStrategy(it, stops) }
        return ControlResult(
            affected = books.map { it.name } + strategies.map { it.name },
            streamStops = stops,
            bookHalts = cleared.toMap(),
        )
    }

    private fun resumeOne(name: String): ControlResult {
        registry.getPortfolio(name)?.let { book ->
            val cleared = book.supervisor.resumeBook()
            return ControlResult(
                affected = listOf(name),
                bookHalts = cleared?.let { mapOf(name to it.reason) }.orEmpty(),
            )
        }
        val resolved = childName(name)
        val handle = registry.get(resolved) ?: return ControlResult(affected = emptyList(), unknown = listOf(name))
        val book = handle.childMeta?.parent?.let(registry::getPortfolio)
        val bookHalt = book?.supervisor?.bookHalt()
        if (book != null && bookHalt != null) {
            val why =
                "portfolio ${book.name} is halted (${bookHalt.scope.name}): ${bookHalt.reason}; " +
                    "`qkt resume ${book.name}` clears the book and the halts it put on its children"
            return ControlResult(affected = emptyList(), refused = mapOf(resolved to why))
        }
        val stops = mutableListOf<StreamStopRelease>()
        resumeStrategy(handle, stops)
        return ControlResult(affected = listOf(resolved), streamStops = stops)
    }

    private fun resumeStrategy(
        handle: StrategyHandle,
        stops: MutableList<StreamStopRelease>,
    ) {
        handle.live.resume()
        stops += handle.live.releaseStreamStops()
    }

    private companion object {
        /** `book:alias` is how operators also write the child `book/alias`; registry names never hold a `:`. */
        fun childName(name: String): String = name.replaceFirst(':', '/')
    }
}
