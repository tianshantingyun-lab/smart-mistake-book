package com.tingyun.smartmistakebook.core.mastery.database;

/**
 * Host-owned, monotonic scope lease checked at transaction start and immediately before insert.
 *
 * <p>The implementation must throw when the exact tutor scope is no longer current and must
 * return a larger epoch after question, conversation, mode, cancellation, or request changes.
 */
@FunctionalInterface
public interface LearnerMasteryOpenResponseScopeLease {
    long requireCurrentEpoch();
}
