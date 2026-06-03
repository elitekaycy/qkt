package com.qkt.cli.daemon

/** The text reply sent back to an operator after dispatching a [ControlCommand]. */
data class CommandReply(
    val text: String,
)
