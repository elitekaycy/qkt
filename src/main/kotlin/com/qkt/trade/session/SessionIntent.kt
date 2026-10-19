package com.qkt.trade.session

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.Buy
import com.qkt.dsl.ast.Cancel
import com.qkt.dsl.ast.Close
import com.qkt.dsl.ast.Sell
import com.qkt.dsl.ast.WhenThen
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.substituteIterVar
import java.security.MessageDigest

/** One intent as a session runs it: its [action] on the session's aliases, the [symbol] it acts on. */
data class SessionIntent(
    val source: String,
    val sha256: String,
    val action: ActionAst,
    /** The symbol the action targets, or null for `CLOSE_ALL` / `CANCEL_ALL`. */
    val symbol: String?,
)

/**
 * Reads intent [source] (a complete strategy with one `WHEN true THEN <action>` rule, as `qkt bot`
 * renders it) and rewrites every stream it declares to the session's alias for that symbol, so the
 * action runs on the series the session declares. A symbol the session does not declare is refused.
 */
fun sessionIntent(
    source: String,
    streams: BotSessionStreams,
): SessionIntent {
    val ast =
        when (val parsed = Dsl.parse(source)) {
            is ParseResult.Success -> parsed.value
            is ParseResult.Failure ->
                error(
                    "intent failed to parse: ${parsed.errors.joinToString("; ") { it.message }}",
                )
        }
    var rule =
        ast.rules.singleOrNull() as? WhenThen
            ?: error("an intent holds exactly one WHEN/THEN rule, got ${ast.rules.size}")
    // Two passes through placeholders, so an intent alias that happens to equal a session alias
    // cannot be renamed twice.
    val targets =
        ast.streams.associate { decl ->
            decl.alias to
                (
                    streams.alias(decl.qktSymbol)
                        ?: error(
                            "${decl.qktSymbol} is not a session symbol; the session declares ${streams.describe()}",
                        )
                )
        }
    targets.keys.forEachIndexed { i, alias -> rule = substituteIterVar(rule, alias, "__intent$i") }
    targets.values.forEachIndexed { i, alias -> rule = substituteIterVar(rule, "__intent$i", alias) }
    val symbolOf = ast.streams.associate { it.alias to it.qktSymbol }
    val symbol =
        when (val a = ast.rules.single().let { (it as WhenThen).action }) {
            is Buy -> symbolOf[a.stream]
            is Sell -> symbolOf[a.stream]
            is Close -> symbolOf[a.stream]
            is Cancel -> symbolOf[a.stream]
            else -> null
        }
    require(rule.cond is BoolLit && (rule.cond as BoolLit).value) { "an intent's rule is WHEN true; got ${rule.cond}" }
    return SessionIntent(source, sha256(source), rule.action, symbol)
}

private fun sha256(text: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
