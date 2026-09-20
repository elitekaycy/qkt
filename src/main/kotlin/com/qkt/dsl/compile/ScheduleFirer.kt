package com.qkt.dsl.compile

import com.qkt.strategy.Signal
import com.qkt.strategy.StrategyContext

private val scheduleLog =
    org.slf4j.LoggerFactory.getLogger("com.qkt.dsl.compile.ScheduleFire")

/**
 * Registers a compiled strategy's `SCHEDULE` actions with the [ScheduleRunner] and fires each one
 * against the latest closed candle, skipping (to retry later) while no stream has closed a bar.
 */
internal class ScheduleFirer(
    private val schedules: List<CompiledSchedule>,
    private val streams: Map<String, HubKey>,
    private val snapshotStore: SnapshotStore,
    private val sequenceRuntime: SequenceRuntime,
    private val binding: StrategyHubBinding,
) {
    fun bind(
        runner: ScheduleRunner,
        ctx: StrategyContext,
        nowMs: Long,
        emit: (Signal) -> Unit,
    ) {
        for (sched in schedules) {
            runner.register(
                strategyId = ctx.strategyId,
                schedule = sched.decl,
                emitAt = { fireAt -> fireSchedule(sched, ctx, emit, fireAt) },
                nowMs = nowMs,
            )
        }
    }

    private fun fireSchedule(
        sched: CompiledSchedule,
        ctx: StrategyContext,
        emit: (Signal) -> Unit,
        fireAt: Long,
    ): Boolean {
        val hub = binding.boundHub ?: return false
        val syntheticCandle =
            binding.latestKnownCandle(hub) ?: run {
                scheduleLog.warn(
                    "schedule fire skipped for strategy={} — no stream has a closed bar yet " +
                        "(warmup not complete). Trigger will retry on the next fire time.",
                    ctx.strategyId,
                )
                return false
            }
        val ec =
            EvalContext(
                candle = syntheticCandle,
                streams = streams,
                lets = emptyMap(),
                strategyContext = ctx,
                snapshotStore = snapshotStore,
                hub = hub,
                currentAlias = null,
                evaluationTimeMs = fireAt,
                sequences = sequenceRuntime,
            )
        for (sig in sched.action(ec)) emit(sig)
        return true
    }
}
