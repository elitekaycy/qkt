package com.qkt.dsl.compile

/**
 * True when [stream] is not the stream whose bar is being evaluated — i.e. this action is
 * ordering on a symbol other than the one that triggered the rule.
 */
internal fun EvalContext.isCrossStream(stream: String): Boolean {
    val target = streams[stream] ?: return false
    val current = currentAlias
    return if (current != null) current != stream else candle.symbol != target.qktSymbol
}
