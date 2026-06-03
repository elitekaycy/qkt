package com.qkt.cli.daemon

/**
 * Inbound-transport lifecycle for operator commands.
 *
 * A channel receives operator commands on its own transport (e.g. Telegram, stdin),
 * authorizes the sender, runs them through [CommandParser] + [CommandDispatcher], and
 * replies on the same transport.
 *
 * e.g. a Telegram channel receives "/halt gold" → parses to Halt(Strategy("gold")) →
 * dispatches → replies "halted: gold" back to the same chat.
 */
interface CommandChannel {
    fun start()

    fun close()
}
