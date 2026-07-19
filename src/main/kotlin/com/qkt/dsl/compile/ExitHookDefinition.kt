package com.qkt.dsl.compile

import com.qkt.dsl.ast.ActionAst
import com.qkt.dsl.ast.ExitHooksAst
import com.qkt.execution.ExitReason
import com.qkt.strategy.Signal
import java.security.MessageDigest

/** Durable identity attached to signals produced by a hook-bearing action. */
data class ExitHookRef(
    val definitionId: String,
    val fingerprint: String,
)

internal data class CompiledExitHookDefinition(
    val ref: ExitHookRef,
    val onStop: List<(EvalContext) -> List<Signal>>,
    val onTakeProfit: List<(EvalContext) -> List<Signal>>,
    val onClose: List<(EvalContext) -> List<Signal>>,
) {
    fun execute(
        reason: ExitReason,
        context: EvalContext,
    ): List<Signal> {
        val actions =
            when (reason) {
                ExitReason.STOP -> onStop
                ExitReason.TAKE_PROFIT -> onTakeProfit
                ExitReason.CLOSE -> onClose
            }
        return actions.flatMap { it(context) }
    }
}

/**
 * Per-compiled-strategy registry of executable exit-hook definitions.
 *
 * [fingerprintContext] salts durable identities with stream/basket bindings that
 * can change the meaning of an otherwise identical hook AST.
 */
class ExitHookCatalog(
    private val fingerprintContext: String = "",
) {
    private val definitions = linkedMapOf<String, CompiledExitHookDefinition>()

    internal fun register(
        hooks: ExitHooksAst,
        compiler: (ActionAst) -> (EvalContext) -> List<Signal>,
    ): ExitHookRef {
        val fingerprint =
            MessageDigest
                .getInstance("SHA-256")
                .digest("$fingerprintContext\n$hooks".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        val id = "exit-hook-${definitions.size + 1}-${fingerprint.take(12)}"
        val ref = ExitHookRef(id, fingerprint)
        definitions[id] =
            CompiledExitHookDefinition(
                ref = ref,
                onStop = hooks.onStop.map(compiler),
                onTakeProfit = hooks.onTakeProfit.map(compiler),
                onClose = hooks.onClose.map(compiler),
            )
        return ref
    }

    internal fun definition(ref: ExitHookRef): CompiledExitHookDefinition? =
        definitions[ref.definitionId]?.takeIf { it.ref.fingerprint == ref.fingerprint }

    internal fun references(): Map<String, ExitHookRef> = definitions.mapValues { it.value.ref }
}
