package com.qkt.observability

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** An observability fault never leaks into the trading path: a failing submit map is logged and swallowed. */
class LatencyRegistryFailureTest {
    private fun replaceSubmitMap(
        registry: LatencyRegistry,
        map: MutableMap<String, Long>,
    ) {
        LatencyRegistry::class.java
            .getDeclaredField("submitNanos")
            .also { it.isAccessible = true }
            .set(registry, map)
    }

    @Test
    fun `a submit map that throws on write is swallowed`() {
        val registry = LatencyRegistry(enabled = true, strategyIds = listOf("s1"))
        replaceSubmitMap(
            registry,
            object : HashMap<String, Long>() {
                override fun put(
                    key: String,
                    value: Long,
                ): Long? = throw IllegalStateException("map is broken")
            },
        )

        registry.recordSubmit("ord-1")

        assertThat(registry.enabled).isTrue()
    }

    @Test
    fun `a submit time in the future is dropped instead of thrown`() {
        val registry = LatencyRegistry(enabled = true, strategyIds = listOf("s1"))
        replaceSubmitMap(registry, mutableMapOf("ord-1" to Long.MAX_VALUE))

        registry.observeFill("ord-1", "s1")

        assertThat(registry.pendingSubmits()).isZero()
    }
}
