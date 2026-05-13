package com.qkt.app

/**
 * Thrown by [LiveSession.start] when persisted leg state and broker positions don't
 * agree, and the operator hasn't opted into ignore-mismatch attachment via the
 * `--reconcile=ignore-mismatches` flag.
 *
 * The control plane translates this into HTTP 409 Conflict on deploy.
 */
class ReconcileException(
    message: String,
) : RuntimeException(message)
