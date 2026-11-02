package com.qkt.cli.daemon

import com.qkt.cli.daemon.portfolio.PortfolioDeployer
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.dsl.portfolio.PortfolioLoader
import java.nio.file.Files
import java.nio.file.Path

/** One `--load-dir` file that failed to deploy at startup and is owed a retry (#1055). */
internal data class FailedAutoDeploy(
    val name: String,
    val file: Path,
    val message: String,
)

/**
 * The daemon's pass through `--load-dir`, deploying its files one at a time. [underway] from construction, before any
 * account opens, until the pass ends: a strategy coming up meanwhile may not be the last one on its account.
 */
internal class StartupDeploys(
    private val dir: String?,
) {
    @Volatile private var finished = false

    /** Whether strategies are still to come. Never so without a [dir]. */
    fun underway(): Boolean = dir != null && !finished

    /**
     * Auto-deploys every `.qkt` in the directory, then calls [onFinished], whatever happened. Returns the files that
     * failed so the caller can hand them to an [AutoDeployRetrier]: a transient failure at boot (venue gap, gateway
     * reconnecting) must not leave the daemon idle (#1055).
     */
    fun run(
        registry: StrategyRegistry,
        portfolioDeployer: PortfolioDeployer? = null,
        onFinished: () -> Unit = {},
        onDeployError: (name: String, message: String) -> Unit = { _, _ -> },
    ): List<FailedAutoDeploy> {
        try {
            if (dir == null) return emptyList()
            return deployAll(Path.of(dir), registry, portfolioDeployer, onDeployError)
        } finally {
            finished = true
            onFinished()
        }
    }

    private fun deployAll(
        path: Path,
        registry: StrategyRegistry,
        portfolioDeployer: PortfolioDeployer?,
        onDeployError: (name: String, message: String) -> Unit,
    ): List<FailedAutoDeploy> {
        if (!Files.isDirectory(path)) {
            System.err.println("[WARN] --load-dir $path is not a directory; skipping")
            return emptyList()
        }
        val failed = ArrayList<FailedAutoDeploy>()
        Files.list(path).use { stream ->
            for (file in stream.toList()) {
                if (!file.toString().endsWith(".qkt")) continue
                val name = file.fileName.toString().removeSuffix(".qkt")
                runCatching { deployLoadDirFile(name, file, registry, portfolioDeployer) }
                    .onSuccess { println("[INFO] auto-deployed $name from $file") }
                    .onFailure { e ->
                        val message = e.message ?: e::class.java.simpleName
                        System.err.println("[WARN] failed to auto-deploy $name: $message")
                        failed += FailedAutoDeploy(name, file, message)
                        onDeployError(name, message)
                    }
            }
        }
        return failed
    }
}

/** Deploy one `--load-dir` file as a strategy or portfolio; throws with the deploy error. */
internal fun deployLoadDirFile(
    name: String,
    file: Path,
    registry: StrategyRegistry,
    portfolioDeployer: PortfolioDeployer?,
): String =
    when (val parsed = Dsl.parseFileAny(file)) {
        is ParseResult.Success ->
            when (parsed.value) {
                is ParsedFile.StrategyFile -> {
                    registry.deploy(name, file)
                    "strategy"
                }
                is ParsedFile.PortfolioFile -> {
                    check(portfolioDeployer != null) {
                        "portfolio deploy not configured on this daemon"
                    }
                    val compiled = PortfolioLoader.load(file)
                    val record = portfolioDeployer.deploy(name, compiled)
                    registry.registerPortfolio(record)
                    "portfolio"
                }
            }
        is ParseResult.Failure -> {
            val msg = parsed.errors.joinToString("\n") { "${it.line}:${it.col} ? ${it.message}" }
            error("parse failure for $file:\n$msg")
        }
    }
