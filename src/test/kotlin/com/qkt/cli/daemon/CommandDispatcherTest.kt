package com.qkt.cli.daemon

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CommandDispatcherTest {
    private class FakeDaemonControl(
        private var statusReport: StatusReport = StatusReport(emptyList()),
        private var haltResult: ControlResult = ControlResult(emptyList()),
        private var resumeResult: ControlResult = ControlResult(emptyList()),
    ) : DaemonControl {
        var lastHaltTarget: Target? = null
        var lastResumeTarget: Target? = null

        fun withStatus(report: StatusReport): FakeDaemonControl = apply { statusReport = report }

        fun withHaltResult(result: ControlResult): FakeDaemonControl = apply { haltResult = result }

        fun withResumeResult(result: ControlResult): FakeDaemonControl = apply { resumeResult = result }

        override fun halt(target: Target): ControlResult {
            lastHaltTarget = target
            return haltResult
        }

        override fun resume(target: Target): ControlResult {
            lastResumeTarget = target
            return resumeResult
        }

        override fun status(): StatusReport = statusReport
    }

    private val usageText = "commands:\n/status\n/halt [name]\n/resume [name]\n/help"

    @Test
    fun `Status with two strategies formats exact text`() {
        val control =
            FakeDaemonControl().withStatus(
                StatusReport(
                    listOf(
                        StrategyStatus("gold", running = true, halted = true),
                        StrategyStatus("silver", running = true, halted = false),
                    ),
                ),
            )
        val reply = CommandDispatcher(control).dispatch(ControlCommand.Status)
        assertThat(reply.text).isEqualTo("strategies (2):\n- gold: running, halted\n- silver: running")
    }

    @Test
    fun `Status with empty report returns no strategies deployed`() {
        val control = FakeDaemonControl().withStatus(StatusReport(emptyList()))
        val reply = CommandDispatcher(control).dispatch(ControlCommand.Status)
        assertThat(reply.text).isEqualTo("no strategies deployed")
    }

    @Test
    fun `Halt All delegates to control and formats affected names`() {
        val control =
            FakeDaemonControl().withHaltResult(
                ControlResult(affected = listOf("gold", "silver")),
            )
        val reply = CommandDispatcher(control).dispatch(ControlCommand.Halt(Target.All))
        assertThat(control.lastHaltTarget).isEqualTo(Target.All)
        assertThat(reply.text).isEqualTo("halted: gold, silver")
    }

    @Test
    fun `Halt Strategy with unknown name returns unknown strategy message`() {
        val control =
            FakeDaemonControl().withHaltResult(
                ControlResult(affected = emptyList(), unknown = listOf("foo")),
            )
        val reply = CommandDispatcher(control).dispatch(ControlCommand.Halt(Target.Strategy("foo")))
        assertThat(control.lastHaltTarget).isEqualTo(Target.Strategy("foo"))
        assertThat(reply.text).isEqualTo("unknown strategy: foo")
    }

    @Test
    fun `Resume All delegates to control and formats affected names`() {
        val control =
            FakeDaemonControl().withResumeResult(
                ControlResult(affected = listOf("gold", "silver")),
            )
        val reply = CommandDispatcher(control).dispatch(ControlCommand.Resume(Target.All))
        assertThat(control.lastResumeTarget).isEqualTo(Target.All)
        assertThat(reply.text).isEqualTo("resumed: gold, silver")
    }

    @Test
    fun `Help returns usage text`() {
        val reply = CommandDispatcher(FakeDaemonControl()).dispatch(ControlCommand.Help)
        assertThat(reply.text).isEqualTo(usageText)
    }

    @Test
    fun `Unknown returns unknown command prefix and usage`() {
        val reply = CommandDispatcher(FakeDaemonControl()).dispatch(ControlCommand.Unknown("/bogus"))
        assertThat(reply.text).isEqualTo("unknown command: /bogus\n$usageText")
    }
}
