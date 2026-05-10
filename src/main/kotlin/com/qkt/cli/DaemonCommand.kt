package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.ControlPlane
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyHandle
import com.qkt.cli.daemon.StrategyRegistry
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import com.qkt.marketdata.source.MarketSource
import java.time.Instant
import java.util.concurrent.CountDownLatch

class DaemonCommand(
    private val args: Args,
    private val sourceFactory: (List<String>) -> MarketSource = ::defaultTradingViewSource,
) {
    fun run(): Int {
        // Sub-subcommand dispatch (implemented further in Task 9): `qkt daemon stop|status`.
        // The sub-subcommand, if present, is always argv[1] before any flags.
        return when (val sub = args.firstNonOption()) {
            null -> startDaemon()
            "stop" -> stopDaemon()
            "status" -> statusDaemon()
            else -> {
                System.err.println("qkt: unknown daemon subcommand '$sub'")
                ExitCodes.ARG_ERROR
            }
        }
    }

    private fun startDaemon(): Int {
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val sharedHub =
            com.qkt.dsl.compile
                .CandleHub()
        val registry =
            StrategyRegistry(
                StrategyHandle.RealFactory(
                    stateDir = stateDir,
                    marketSourceProvider = sourceFactory,
                    candleHub = sharedHub,
                ),
            )
        val startedAt = Instant.now()
        val stopLatch = CountDownLatch(1)

        val portfolioDeployer =
            com.qkt.cli.daemon.portfolio
                .PortfolioDeployer(
                    stateDir = stateDir,
                    marketSourceProvider = sourceFactory,
                )
        val plane =
            ControlPlane(
                registry = registry,
                bind = "127.0.0.1",
                port = args.option("control-port")?.toIntOrNull() ?: 0,
                startedAt = startedAt,
                shutdownHook = { stopLatch.countDown() },
                stateDir = stateDir,
                portfolioDeployer = portfolioDeployer,
            )
        plane.start()
        stateDir.writeControlPort(plane.boundPort)

        println("[INFO] qkt ${BuildInfo.VERSION} daemon starting")
        println("[INFO] state directory: ${stateDir.root}")
        println(
            "[INFO] control plane: http://127.0.0.1:${plane.boundPort} " +
                "(state file: ${stateDir.controlPortFile})",
        )

        loadDirIfRequested(args.option("load-dir"), registry)

        println("[INFO] daemon ready")

        val shutdown =
            Thread {
                try {
                    println("[INFO] stopping daemon")
                    val n = registry.list().size
                    if (n > 0) println("[INFO] gracefully stopping $n strateg${if (n == 1) "y" else "ies"}")
                    registry.stopAll()
                    plane.close()
                    stateDir.deleteControlPort()
                    println("[INFO] daemon stopped")
                } finally {
                    stopLatch.countDown()
                }
            }
        Runtime.getRuntime().addShutdownHook(shutdown)

        return try {
            stopLatch.await()
            // If the latch was tripped programmatically (POST /shutdown), do the cleanup ourselves.
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
            runCatching { registry.stopAll() }
            runCatching { plane.close() }
            runCatching { stateDir.deleteControlPort() }
            ExitCodes.SUCCESS
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            ExitCodes.SUCCESS
        }
    }

    private fun stopDaemon(): Int {
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = controlClient(stateDir)
        return try {
            client.shutdown()
            println("[INFO] daemon stop accepted")
            ExitCodes.SUCCESS
        } catch (e: ControlClient.NoDaemonRunningException) {
            System.err.println("qkt: error: ${e.message}")
            ExitCodes.USER_ERROR
        } catch (e: ControlClient.DaemonError) {
            System.err.println("qkt: error: shutdown failed (${e.code}): ${e.body}")
            ExitCodes.USER_ERROR
        }
    }

    private fun statusDaemon(): Int {
        val stateDir = StateDir.resolve(args.option("state-dir"))
        val client = controlClient(stateDir)
        return try {
            val body = client.health()
            val port = stateDir.readControlPort() ?: 0
            if (args.flag("json")) {
                println(body)
            } else {
                println("control port: $port")
                println("state file: ${stateDir.controlPortFile}")
                println(body)
            }
            ExitCodes.SUCCESS
        } catch (e: ControlClient.NoDaemonRunningException) {
            System.err.println("qkt: error: ${e.message}")
            ExitCodes.USER_ERROR
        } catch (e: ControlClient.DaemonError) {
            System.err.println("qkt: error: status failed (${e.code}): ${e.body}")
            ExitCodes.USER_ERROR
        }
    }

    private fun loadDirIfRequested(
        dir: String?,
        registry: StrategyRegistry,
    ) {
        if (dir == null) return
        val path =
            java.nio.file.Path
                .of(dir)
        if (!java.nio.file.Files
                .isDirectory(path)
        ) {
            System.err.println("[WARN] --load-dir $dir is not a directory; skipping")
            return
        }
        java.nio.file.Files.list(path).use { stream ->
            for (file in stream.toList()) {
                if (!file.toString().endsWith(".qkt")) continue
                val name = file.fileName.toString().removeSuffix(".qkt")
                runCatching { registry.deploy(name, file) }
                    .onSuccess { println("[INFO] auto-deployed $name from $file") }
                    .onFailure { System.err.println("[WARN] failed to auto-deploy $name: ${it.message}") }
            }
        }
    }

    private fun controlClient(stateDir: StateDir): ControlClient = ControlClient(stateDir)

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun defaultTradingViewSource(symbols: List<String>): MarketSource = TradingViewMarketSource.connect()
    }
}
