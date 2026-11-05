package com.qkt.cli.daemon.portfolio

import com.qkt.risk.HaltScope

/**
 * A portfolio book's halt as an operator sees it: why it holds, how long it holds ([HaltScope.TRANSIENT] for a book
 * held off entries while unreadable, `DAILY` or `PERSISTENT` for a tripped book limit), and since when (epoch ms,
 * null when not recorded).
 */
data class BookHalt(
    val reason: String,
    val scope: HaltScope,
    val haltedAtMs: Long?,
)
