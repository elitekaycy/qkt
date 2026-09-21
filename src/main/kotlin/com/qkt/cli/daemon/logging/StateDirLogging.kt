package com.qkt.cli.daemon.logging

import java.nio.file.Path

/**
 * Points the per-strategy log files at the `--state-dir` the process was started with.
 *
 * `logback.xml` writes each strategy's log under `${QKT_STATE_DIR}/logs`, and logback resolves
 * that name from system properties before the environment. The environment variable is what a
 * container sets; a daemon started with the flag alone wrote its strategy logs under the default
 * state home, while `qkt logs` read the flag's directory and showed nothing. Publishing the flag
 * as the system property makes both agree. Must run before the first logger is created.
 *
 * e.g. `qkt daemon start --state-dir /srv/qkt` logs `alpha` to `/srv/qkt/logs/alpha.log`.
 */
internal object StateDirLogging {
    const val PROPERTY: String = "QKT_STATE_DIR"

    /** The value of `--state-dir` in [argv] (`--state-dir X` or `--state-dir=X`), made absolute. */
    fun stateDirOf(argv: Array<String>): String? {
        val index = argv.indexOf("--state-dir")
        val raw =
            when {
                index >= 0 -> argv.getOrNull(index + 1)?.takeUnless { it.startsWith("--") }
                else -> argv.firstOrNull { it.startsWith("--state-dir=") }?.substringAfter('=')
            }
        return raw?.takeIf { it.isNotBlank() }?.let {
            Path
                .of(it)
                .toAbsolutePath()
                .normalize()
                .toString()
        }
    }

    fun bind(argv: Array<String>) {
        stateDirOf(argv)?.let { System.setProperty(PROPERTY, it) }
    }
}
