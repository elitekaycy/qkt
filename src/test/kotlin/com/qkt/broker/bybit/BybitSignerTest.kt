package com.qkt.broker.bybit

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitSignerTest {
    @Test
    fun `produces deterministic HMAC-SHA256 hex`() {
        val signer = BybitSigner(secret = "test-secret")
        val sig = signer.signHex("hello world")
        assertThat(sig).isEqualTo("046e2496e13e0bfd8dbef84244dd188311a48086646355161bc4ad0769a49cf4")
    }

    @Test
    fun `different inputs produce different signatures`() {
        val signer = BybitSigner(secret = "k")
        val a = signer.signHex("a")
        val b = signer.signHex("b")
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `same input always produces same signature`() {
        val signer = BybitSigner(secret = "k")
        val a = signer.signHex("payload")
        val b = signer.signHex("payload")
        assertThat(a).isEqualTo(b)
    }
}
