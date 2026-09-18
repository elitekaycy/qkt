package com.qkt.connectivity

/** A credential. Prints as `Secret(***)` so it cannot leak into logs, state or notifications. */
@JvmInline
value class Secret(
    private val value: String,
) {
    /** The credential itself — call only where it is handed to the venue client. */
    fun reveal(): String = value

    override fun toString(): String = "Secret(***)"
}
