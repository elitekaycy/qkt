package com.qkt.connectivity

import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves an account's credential fields.
 *
 * Highest precedence first:
 * 1. environment `QKT_BROKER_<NAME>_<FIELD>` — the override MT5 profiles already honour;
 * 2. the config value — `env:VAR`, `${VAR}`, `file:/path` (trailing newline trimmed, for Docker
 *    secrets), or a literal.
 *
 * A reference to a variable or file that does not exist is an error naming the account and
 * field, never a silently empty credential.
 */
class SecretResolver(
    private val env: Map<String, String>,
    private val readFile: (Path) -> String = { Files.readString(it) },
) {
    /** The resolved value of [field] on [account], or null when the entry does not set it. */
    fun resolve(
        account: AccountConfig,
        field: String,
    ): Secret? {
        val overrideKey = "QKT_BROKER_${account.name.uppercase().replace('-', '_')}_${field.uppercase()}"
        env[overrideKey]?.let { return Secret(it) }
        val raw = account.setting(field) ?: return null
        val where = "${account.name}.$field"
        val value =
            when {
                raw.startsWith("env:") -> envValue(raw.removePrefix("env:"), where)
                raw.startsWith("\${") && raw.endsWith("}") -> envValue(raw.substring(2, raw.length - 1), where)
                raw.startsWith("file:") -> readFile(Path.of(raw.removePrefix("file:"))).trimEnd('\n', '\r')
                else -> raw
            }
        return Secret(value)
    }

    private fun envValue(
        name: String,
        where: String,
    ): String = env[name] ?: error("$where references environment variable $name, which is not set")
}
