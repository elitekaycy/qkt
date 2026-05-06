package com.qkt.broker.bybit

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BybitSigner(
    private val secret: String,
) {
    fun signHex(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }
    }
}
