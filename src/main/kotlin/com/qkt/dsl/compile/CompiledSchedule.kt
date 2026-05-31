package com.qkt.dsl.compile

import com.qkt.dsl.ast.ScheduleDecl
import com.qkt.strategy.Signal

/**
 * One compiled `SCHEDULE` clause: the parsed [decl] plus the pre-compiled
 * action lambda. The lambda is invoked by [ScheduleRunner] whenever any of
 * the clause's triggers fires; it receives a freshly-built [EvalContext]
 * (with `candle = null` because schedules are clock-driven) and returns the
 * resulting [Signal] list to be emitted (#77).
 */
internal class CompiledSchedule(
    val decl: ScheduleDecl,
    val action: (EvalContext) -> List<Signal>,
)
