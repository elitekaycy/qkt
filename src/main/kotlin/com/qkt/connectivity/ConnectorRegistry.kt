package com.qkt.connectivity

import java.util.ServiceLoader

/**
 * Every [Connector] qkt can use, keyed by [ConnectorSpec.type].
 *
 * [discover] finds connectors registered in `META-INF/services/com.qkt.connectivity.Connector` —
 * the built-in ones today, plugin JARs later — so adding a connector never edits this class.
 */
class ConnectorRegistry(
    connectors: List<Connector>,
) {
    private val byType: Map<String, Connector> =
        connectors
            .groupBy { it.spec.type }
            .mapValues { (type, claimants) ->
                require(claimants.size == 1) { "more than one connector claims type '$type'" }
                claimants.single()
            }

    /** The connector for [type], or null when none is installed. */
    fun find(type: String): Connector? = byType[type]

    /** Installed connector types, sorted. */
    val types: List<String> get() = byType.keys.sorted()

    companion object {
        /** Every connector registered as a service visible to [loader]. */
        fun discover(loader: ClassLoader = Connector::class.java.classLoader): ConnectorRegistry =
            ConnectorRegistry(ServiceLoader.load(Connector::class.java, loader).toList())
    }
}
