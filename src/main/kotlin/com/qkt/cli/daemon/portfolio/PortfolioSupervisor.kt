package com.qkt.cli.daemon.portfolio

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

    private fun applyAlwaysRunRules() {
        // Filled in T7.
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
