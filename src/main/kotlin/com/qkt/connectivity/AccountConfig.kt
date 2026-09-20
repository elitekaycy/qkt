package com.qkt.connectivity

/**
 * One account's entry under `brokers:` in `qkt.config.yaml`.
 *
 * [settings] holds the entry's scalar fields exactly as written — values are not resolved, so read
 * credentials through [ConnectorContext.secrets]. The nested blocks any connector may use are
 * parsed once by the config loader: [tradingHours] (`calendars`, ordered `pattern -> calendar`),
 * [symbolAliases] (`aliases`), [disabledOrderTypes] (`capability_restrictions`) and
 * [instrumentOverrides] (`instrument_overrides`, `symbol -> field -> value`).
 */
data class AccountConfig(
    val name: String,
    val type: String,
    val settings: Map<String, String>,
    val tradingHours: List<Pair<String, String>> = emptyList(),
    val symbolAliases: Map<String, String> = emptyMap(),
    val disabledOrderTypes: List<String> = emptyList(),
    val instrumentOverrides: Map<String, Map<String, String>> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "account name must not be blank" }
    }

    /** The strategy symbol prefix this account serves: the upper-cased name plus `:`. */
    val symbolPrefix: String get() = "${name.uppercase()}:"

    /** The raw scalar value of [field], or null when the entry does not set it. */
    fun setting(field: String): String? = settings[field]
}
