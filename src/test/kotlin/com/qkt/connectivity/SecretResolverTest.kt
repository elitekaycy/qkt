package com.qkt.connectivity

import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SecretResolverTest {
    private fun account(vararg settings: Pair<String, String>) =
        AccountConfig(name = "bybit_linear", type = "bybit", settings = mapOf(*settings))

    @Test
    fun `env override beats the config value`() {
        val resolver =
            SecretResolver(env = mapOf("QKT_BROKER_BYBIT_LINEAR_API_KEY" to "from-override", "K" to "from-ref"))

        assertThat(resolver.resolve(account("api_key" to "env:K"), "api_key")?.reveal()).isEqualTo("from-override")
    }

    @Test
    fun `env, dollar and file references resolve`() {
        val file = Files.createTempFile("secret", ".txt").also { Files.writeString(it, "from-file\n") }
        val resolver = SecretResolver(env = mapOf("K" to "from-env"))

        assertThat(resolver.resolve(account("a" to "env:K"), "a")?.reveal()).isEqualTo("from-env")
        assertThat(resolver.resolve(account("a" to "\${K}"), "a")?.reveal()).isEqualTo("from-env")
        assertThat(resolver.resolve(account("a" to "file:$file"), "a")?.reveal()).isEqualTo("from-file")
        assertThat(resolver.resolve(account("a" to "literal"), "a")?.reveal()).isEqualTo("literal")
    }

    @Test
    fun `absent field is null and a missing reference is an error`() {
        val resolver = SecretResolver(env = emptyMap())

        assertThat(resolver.resolve(account(), "api_key")).isNull()
        assertThatThrownBy { resolver.resolve(account("api_key" to "env:NOPE"), "api_key") }
            .hasMessageContaining("bybit_linear.api_key")
            .hasMessageContaining("NOPE")
    }

    @Test
    fun `a secret never prints its value`() {
        assertThat(Secret("hunter2").toString()).isEqualTo("Secret(***)")
        assertThat("${Secret("hunter2")}").doesNotContain("hunter2")
    }
}
