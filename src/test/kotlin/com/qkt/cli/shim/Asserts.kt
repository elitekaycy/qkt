package com.qkt.cli.shim

import org.junit.jupiter.api.Assertions

typealias Test = org.junit.jupiter.api.Test

fun <T> assertEquals(
    expected: T,
    actual: T,
    message: String? = null,
) = Assertions.assertEquals(expected, actual, message)

fun assertTrue(
    condition: Boolean,
    message: String? = null,
) = Assertions.assertTrue(condition, message)

fun assertNull(value: Any?) = Assertions.assertNull(value)

fun <T : Any> assertNotNull(value: T?): T = Assertions.assertNotNull(value).let { value!! }

inline fun <reified T : Throwable> assertFailsWith(crossinline block: () -> Unit): T =
    Assertions.assertThrows(T::class.java) {
        block()
    }
