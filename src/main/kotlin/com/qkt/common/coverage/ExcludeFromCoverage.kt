package com.qkt.common.coverage

/**
 * Marks a declaration the coverage gate skips: process entry points and wiring that only a
 * running process exercises. Every use needs a one-line reason next to it; reviewers reject
 * uses on code a unit test could reach.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.FILE, AnnotationTarget.CONSTRUCTOR)
annotation class ExcludeFromCoverage(
    val reason: String,
)
