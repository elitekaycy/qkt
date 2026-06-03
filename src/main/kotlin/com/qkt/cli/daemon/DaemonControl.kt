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

/**
 * In-process control surface over the running daemon. The HTTP control routes, the CLI
 * (via HTTP), and (later) inbound command channels all go through this one type so the
 * halt/resume logic lives in exactly one place.
 */
interface DaemonControl {
    fun halt(target: Target): ControlResult

    fun resume(target: Target): ControlResult
}

/** [DaemonControl] backed by the live [StrategyRegistry]. */
class RegistryDaemonControl(
    private val registry: StrategyRegistry,
) : DaemonControl {
    override fun halt(target: Target): ControlResult = apply(target) { it.live.halt("operator") }

    override fun resume(target: Target): ControlResult = apply(target) { it.live.resume() }

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
