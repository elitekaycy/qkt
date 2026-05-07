package com.qkt.dsl.ast

sealed interface Window

data object SinceOpen : Window

data class SinceTPast(
    val n: Int,
) : Window {
    init {
        require(n > 0) { "SinceTPast.n must be > 0: $n" }
    }
}
