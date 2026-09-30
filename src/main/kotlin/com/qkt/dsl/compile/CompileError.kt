package com.qkt.dsl.compile

import com.qkt.dsl.ast.RuleAst

/**
 * A strategy that parsed but does not compile: an unknown indicator, alias, function or field,
 * a bad arity, an incomplete bracket, a sizing that cannot resolve, and so on.
 *
 * [line] and [col] are 1-based source coordinates when known. The compiler itself only knows the
 * rule it was compiling, so it sets [line] to that rule's `WHEN` line and leaves [col] null;
 * [CompileErrorLocator] refines both against the source text. Both are null for an error that
 * belongs to no rule (a declaration, a sequence, a schedule).
 */
class CompileError(
    override val message: String,
    val line: Int? = null,
    val col: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** True once the compiler has attributed this error to a rule. */
    val isLocated: Boolean get() = line != null

    companion object {
        /** Wraps any failure as a [CompileError], keeping the message and cause. */
        fun of(t: Throwable): CompileError = t as? CompileError ?: CompileError(t.message ?: t.toString(), cause = t)
    }
}

/**
 * Runs one rule's compilation step and tags a failure with the rule's line. An error that
 * already carries a line (a nested rule step) passes through unchanged.
 */
internal inline fun <T> compilingRule(
    rule: RuleAst,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: CompileError) {
        if (e.isLocated) throw e
        throw CompileError(e.message, rule.line.takeIf { it > 0 }, null, e)
    } catch (e: RuntimeException) {
        throw CompileError(e.message ?: e.toString(), rule.line.takeIf { it > 0 }, null, e)
    }
