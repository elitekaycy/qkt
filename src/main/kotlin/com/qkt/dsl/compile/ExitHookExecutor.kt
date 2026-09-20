package com.qkt.dsl.compile

import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import java.math.BigDecimal

/**
 * Runs a persisted exit hook (`ON_STOP` / `ON_TP` / `ON_CLOSE`) when its position exits, against
 * the bound hub's latest candle, or a flat candle at the exit price before any bar has closed.
 */
internal class ExitHookExecutor(
    private val exitHookCatalog: ExitHookCatalog,
    private val streams: Map<String, HubKey>,
    private val snapshotStore: SnapshotStore,
    private val sequenceRuntime: SequenceRuntime,
    private val binding: StrategyHubBinding,
) {
    fun execute(
        ref: ExitHookRef,
        exit: ExitContext,
        timestampMs: Long,
    ): List<Signal> {
        val definition =
            exitHookCatalog.definition(ref)
                ?: error(
                    "Exit-hook definition '${ref.definitionId}' is missing or its fingerprint changed; " +
                        "refusing to run a stale persisted hook",
                )
        val hub = binding.boundHub ?: error("CompiledStrategy must be bound before an exit hook can execute")
        val ctx = binding.boundContext ?: error("CompiledStrategy context is unavailable for exit-hook execution")
        val candle =
            binding.latestKnownCandle(hub)
                ?: Candle(
                    symbol = streams.values.firstOrNull()?.qktSymbol ?: error("Strategy declares no streams"),
                    open = exit.price,
                    high = exit.price,
                    low = exit.price,
                    close = exit.price,
                    volume = BigDecimal.ZERO,
                    startTime = timestampMs,
                    endTime = timestampMs,
                )
        val eval =
            EvalContext(
                candle = candle,
                streams = streams,
                lets = emptyMap(),
                strategyContext = ctx,
                snapshotStore = snapshotStore,
                hub = hub,
                currentAlias = null,
                evaluationTimeMs = timestampMs,
                sequences = sequenceRuntime,
                exitContext = exit,
            )
        return definition.execute(exit.reason, eval)
    }
}
