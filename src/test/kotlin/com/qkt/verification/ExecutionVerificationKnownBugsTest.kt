package com.qkt.verification

/**
 * Execution scenarios where qkt disagrees with its own docs. Each is held here as a `@Disabled` test with the bug it
 * exposes, asserting the documented behaviour through [ExecutionVerificationTest.verify], so re-enabling it is the
 * proof once the fix lands in its own PR. [KNOWN_BUGS] removes whole scenarios from the main factory.
 */
class ExecutionVerificationKnownBugsTest {
    companion object {
        /** Scenario names excluded from [ExecutionVerificationTest]; each has a `@Disabled` test here. */
        val KNOWN_BUGS: Set<String> = emptySet()
    }
}
