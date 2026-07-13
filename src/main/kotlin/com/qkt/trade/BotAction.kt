package com.qkt.trade

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ActionOpts
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import java.security.MessageDigest

/**
 * A validated one-shot trade extracted from canonical bot DSL source: the exact
 * [source] text (the versioned artifact journaled and egressed with every trade),
 * its [sha256], the resolved broker-prefixed [qktSymbol], and the single parsed
 * entry [action] with its [opts].
 */
data class BotAction(
    val source: String,
    val sha256: String,
    val qktSymbol: String,
    val action: ActionAst,
    val opts: ActionOpts,
)

/**
 * Parses canonical bot strategy [source] (produced by [renderBotStrategy]) and extracts
 * the single BUY/SELL action. Fails on parse errors, on any rule count other than one,
 * and on non-entry actions — the one-shot surface only places entries.
 */
fun parseBotStrategy(source: String): BotAction {
    val ast =
        when (val result = Dsl.parse(source)) {
            is ParseResult.Success -> result.value
            is ParseResult.Failure ->
                error(
                    "canonical bot dsl failed to parse: " +
                        result.errors.joinToString("; ") { it.message },
                )
        }
    val rule =
        ast.rules.singleOrNull() as? WhenThen
            ?: error("bot strategy must contain exactly one WHEN/THEN rule, got ${ast.rules.size}")
    val (stream, opts) =
        when (val action = rule.action) {
            is Buy -> action.stream to action.opts
            is Sell -> action.stream to action.opts
            else -> error("bot strategy action must be BUY or SELL, got ${rule.action::class.simpleName}")
        }
    val decl =
        ast.streams.singleOrNull { it.alias == stream }
            ?: error("bot strategy stream alias '$stream' has no SYMBOLS declaration")
    return BotAction(
        source = source,
        sha256 = sha256(source),
        qktSymbol = decl.qktSymbol,
        action = rule.action,
        opts = opts,
    )
}

private fun sha256(text: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
