package com.tingyun.smartmistakebook.core.mastery.database;

/**
 * Package-private production owner proof.
 *
 * <p>The only cross-module caller lives in the audited core:data split-package bridge. General
 * Kotlin callers cannot name this type, and the constructor is private.
 */
final class LearnerMasteryOwnerKey {
    static final LearnerMasteryOwnerKey INSTANCE = new LearnerMasteryOwnerKey();

    private LearnerMasteryOwnerKey() {}
}
