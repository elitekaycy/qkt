package com.qkt.cli.daemon

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * CLI-side client of the daemon's loopback control plane. Each call resolves the port from the
 * state directory (or [explicitPort]), so a restarted daemon is picked up without reconfiguring.
 * Methods are `open` so command tests can substitute canned daemon replies.
 */
open class ControlClient(
    stateDir: StateDir,
    http: OkHttpClient = defaultHttp(),
    explicitPort: Int? = null,
) {
    /** No daemon is running: the state directory has no control port. */
    class NoDaemonRunningException(
        msg: String,
    ) : RuntimeException(msg)

    /** The daemon answered with a non-2xx [code]; [body] is its reply. */
    class DaemonError(
        val code: Int,
        val body: String,
    ) : RuntimeException("daemon returned $code: $body")

    private val transport = ControlTransport(stateDir, http, explicitPort)

    private fun baseUrl(): String = transport.baseUrl()

    /** Prometheus metrics text. */
    open fun metrics(): String = transport.get("${baseUrl()}/metrics")

    /** Daemon health JSON. */
    open fun health(): String = transport.get("${baseUrl()}/health")

    /** JSON array of deployed strategies. */
    open fun list(): String = transport.get("${baseUrl()}/list")

    /** Daemon-wide status, or one strategy's when [name] is given. */
    open fun status(name: String? = null): String {
        val url = if (name == null) "${baseUrl()}/status" else "${baseUrl()}/status/$name"
        return transport.get(url)
    }

    /** Per-strategy, per-stage latency percentiles. */
    open fun latency(): String = transport.get("${baseUrl()}/latency")

    /** Opens a strategy's log stream; the caller reads and closes the response. */
    fun logs(
        name: String,
        lines: Int? = null,
        since: String? = null,
        follow: Boolean = false,
    ): Response {
        val q =
            buildList {
                if (lines != null) add("lines=$lines")
                if (since != null) add("since=$since")
                if (follow) add("follow=true")
            }.joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
        return transport.open("${baseUrl()}/logs/$name$q")
    }

    /** Asks the daemon to shut down. */
    fun shutdown(): String = transport.post("${baseUrl()}/shutdown")

    /** Stops a strategy, optionally flattening its positions, waiting up to [timeoutMs]. */
    fun stop(
        name: String,
        flatten: Boolean = false,
        timeoutMs: Long? = null,
    ): String {
        val q =
            buildList {
                if (flatten) add("flatten=true")
                if (timeoutMs != null) add("timeout=$timeoutMs")
            }.joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
        return transport.post("${baseUrl()}/stop/$name$q")
    }

    /** Starts a stopped strategy. */
    fun start(name: String): String = transport.post("${baseUrl()}/start/$name")

    /** Halts new entries for one strategy, or all when [name] is null. */
    open fun halt(name: String? = null): String {
        val url = if (name == null) "${baseUrl()}/halt" else "${baseUrl()}/halt/$name"
        return transport.post(url)
    }

    /** Kills one strategy, or all when [name] is null, optionally flattening. */
    open fun kill(
        name: String? = null,
        flatten: Boolean = false,
    ): String {
        val base = if (name == null) "${baseUrl()}/kill" else "${baseUrl()}/kill/$name"
        val url = if (flatten) "$base?flatten=true" else base
        return transport.post(url)
    }

    /** Engine-to-venue reconciliation report for a strategy. */
    open fun reconcile(name: String): String = transport.get("${baseUrl()}/reconcile/$name")

    /** Resumes one halted strategy, or all when [name] is null. */
    open fun resume(name: String? = null): String {
        val url = if (name == null) "${baseUrl()}/resume" else "${baseUrl()}/resume/$name"
        return transport.post(url)
    }

    /** Deploys [file] under [name], with optional reconcile override and promotion waiver. */
    fun deploy(
        name: String,
        file: Path,
        ignoreMismatches: Boolean = false,
        waiver: String? = null,
        waiverReason: String? = null,
    ): String {
        val body = """{"file":"${file.toAbsolutePath()}","name":"$name"}"""
        val q = deployQuery(ignoreMismatches, waiver, waiverReason)
        return transport.postJson("${baseUrl()}/deploy$q", body)
    }

    /**
     * Ask the daemon to validate and replace a deployed strategy or portfolio under its current name.
     */
    open fun resync(
        name: String,
        file: Path,
        dryRun: Boolean = false,
        ignoreMismatches: Boolean = false,
        waiver: String? = null,
        waiverReason: String? = null,
    ): String {
        val body = """{"file":"${file.toAbsolutePath()}","name":"$name","dryRun":$dryRun}"""
        val q = deployQuery(ignoreMismatches, waiver, waiverReason)
        return transport.postJson("${baseUrl()}/resync$q", body)
    }

    private fun deployQuery(
        ignoreMismatches: Boolean,
        waiver: String?,
        waiverReason: String?,
    ): String =
        buildList {
            if (ignoreMismatches) add("reconcile" to "ignore-mismatches")
            if (!waiver.isNullOrBlank()) add("waive" to waiver)
            if (!waiverReason.isNullOrBlank()) add("reason" to waiverReason)
        }.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }
            .let { if (it.isEmpty()) "" else "?$it" }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    companion object {
        /**
         * Control calls are synchronous: deploy/resync of a multi-child portfolio holds one
         * request open for minutes while the daemon swaps sessions. The stock 10s read timeout
         * failed the CLI mid-operation while the daemon completed anyway, so reads wait up to
         * 30 minutes; connecting to a dead daemon still fails fast.
         */
        fun defaultHttp(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofMinutes(30))
                .build()
    }
}
