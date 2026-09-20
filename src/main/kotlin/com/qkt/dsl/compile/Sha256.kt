package com.qkt.dsl.compile

import java.security.MessageDigest

/** Lower-case hex SHA-256 of [value]'s UTF-8 bytes, the form strategy and rule fingerprints use. */
internal fun sha256(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
