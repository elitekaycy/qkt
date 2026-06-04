package com.qkt.chaos

/** Which fault to inject on `submit`. */
enum class SubmitFault { NONE, REJECT, THROW }

/** Immutable description of the faults a [ChaosBroker] injects. */
data class ChaosFaultModel(
    val submitFault: SubmitFault = SubmitFault.NONE,
    val stalePositions: Boolean = false,
)
