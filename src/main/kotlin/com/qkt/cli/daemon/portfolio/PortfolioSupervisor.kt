package com.qkt.cli.daemon.portfolio

import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.PortfolioAst
import com.qkt.marketdata.source.MarketSource
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

class PortfolioSupervisor(
    val ast: PortfolioAst,
    val children: List<ChildHandle>,
    val marketSource: MarketSource?,
) {
    private val log = LoggerFactory.getLogger(PortfolioSupervisor::class.java)
    private val runFlag = AtomicBoolean(false)
    private var thread: Thread? = null

    val running: Boolean get() = runFlag.get()

    fun start() {
        if (!runFlag.compareAndSet(false, true)) return
        applyAlwaysRunRules()
        if (marketSource == null) return
        thread =
            Thread({
                org.slf4j.MDC.put("strategy", ast.name)
                try {
                    tickLoop()
                } finally {
                    org.slf4j.MDC.remove("strategy")
                }
            }, "qkt-portfolio-supervisor-${ast.name}").apply {
                isDaemon = true
                start()
            }
    }

    fun stop() {
        if (!runFlag.compareAndSet(true, false)) return
        thread?.interrupt()
        thread?.join(5000)
        thread = null
    }

    internal fun applyDesired(desired: Map<String, Boolean>) {
        for (child in children) {
            val want = desired[child.alias] ?: false
            val have = child.gateActive.get()
            if (want == have) continue
            if (want) {
                child.gateActive.set(true)
                log.info("${child.alias} activated")
            } else {
                child.gateActive.set(false)
                log.info("${child.alias} deactivated, hold=${child.hold}")
                if (!child.hold) child.flatten()
            }
        }
    }

    private fun applyAlwaysRunRules() {
        val desired = mutableMapOf<String, Boolean>()
        for (alias in children.map { it.alias }) desired[alias] = false
        for (rule in ast.rules) {
            if (rule is AlwaysRun) desired[rule.alias] = true
        }
        applyDesired(desired)
    }

    private fun tickLoop() {
        // Filled in T8.
        while (runFlag.get()) {
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
    }
}
