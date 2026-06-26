package com.qkt.cli.daemon

/** What a control action targets: every strategy, or one by name. */
sealed interface Target {
    data object All : Target

    data class Strategy(
        val name: String,
    ) : Target
}

/**
 * Outcome of a halt/resume. [affected] are the strategy names actually acted on;
 * [unknown] are requested names that weren't deployed.
 */
data class ControlResult(
    val affected: List<String>,
    val unknown: List<String> = emptyList(),
)

/** A point-in-time view of every deployed strategy's run/halt state. */
data class StatusReport(
    val strategies: List<StrategyStatus>,
)

/** One strategy's state: whether its session is running and whether it is currently halted. */
data class StrategyStatus(
    val name: String,
    val running: Boolean,
    val halted: Boolean,
)

/**
 * In-process control surface over the running daemon. The HTTP control routes, the CLI
 * (via HTTP), and (later) inbound command channels all go through this one type so the
 * halt/resume logic lives in exactly one place.
 */
interface DaemonControl {
    fun halt(target: Target): ControlResult

    fun resume(target: Target): ControlResult

    fun status(): StatusReport
}

/** [DaemonControl] backed by the live [StrategyRegistry]. */
class RegistryDaemonControl(
    private val registry: StrategyRegistry,
    private val operatorJournal: OperatorJournal? = null,
) : DaemonControl {
    override fun halt(target: Target): ControlResult {
        val result = apply(target) { it.live.halt("operator") }
        operatorJournal?.record("halt", target, result)
        return result
    }

    override fun resume(target: Target): ControlResult {
        val result = apply(target) { it.live.resume() }
        operatorJournal?.record("resume", target, result)
        return result
    }

    /**
     * Kill switch: halt the strategy (which also cancels its venue-resting pendings via
     * the halt subscriber) and, when [flatten] is set, close every open position. The
     * session stays alive managing state — kill stops the strategy from trading, it
     * does not tear the process down.
     */
    fun kill(
        target: Target,
        flatten: Boolean,
    ): ControlResult {
        val result =
            apply(target) {
                it.live.halt("operator kill")
                if (flatten) it.live.flatten()
            }
        operatorJournal?.record("kill", target, result, mapOf("flatten" to flatten.toString()))
        return result
    }

    override fun status(): StatusReport =
        StatusReport(
            registry.list().map { StrategyStatus(it.name, it.live.running, it.live.isHalted()) },
        )

    private fun apply(
        target: Target,
        action: (StrategyHandle) -> Unit,
    ): ControlResult =
        when (target) {
            Target.All -> {
                val all = registry.list()
                all.forEach(action)
                ControlResult(affected = all.map { it.name })
            }
            is Target.Strategy -> {
                val handle = registry.get(target.name)
                if (handle == null) {
                    ControlResult(affected = emptyList(), unknown = listOf(target.name))
                } else {
                    action(handle)
                    ControlResult(affected = listOf(target.name))
                }
            }
        }
}
