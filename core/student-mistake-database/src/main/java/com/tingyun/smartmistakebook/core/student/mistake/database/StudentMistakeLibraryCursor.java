package com.tingyun.smartmistakebook.core.student.mistake.database;

import java.util.Objects;

/**
 * Opaque continuation token for one immutable learner-library snapshot.
 *
 * <p>Only the owning store can create a cursor. Package-private accessors let the Room adapter
 * validate the snapshot without exposing its protocol fields to feature or UI callers.
 */
public final class StudentMistakeLibraryCursor {
    private final long changeVersion;
    private final String learnerCanonicalFingerprint;
    private final String queryCanonicalFingerprint;
    private final long changedAtEpochMillis;
    private final String problemId;

    private StudentMistakeLibraryCursor(
            long changeVersion,
            String learnerCanonicalFingerprint,
            String queryCanonicalFingerprint,
            long changedAtEpochMillis,
            String problemId) {
        if (changeVersion < 0L) {
            throw new IllegalArgumentException(
                    "Mistake-library cursor version must not be negative");
        }
        if (!learnerCanonicalFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Mistake-library cursor learner fingerprint must be SHA-256");
        }
        if (!queryCanonicalFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Mistake-library cursor query fingerprint must be SHA-256");
        }
        if (changedAtEpochMillis < 0L) {
            throw new IllegalArgumentException(
                    "Mistake-library cursor time must not be negative");
        }
        if (problemId.isBlank()
                || !problemId.equals(problemId.trim())
                || problemId.length() > 256) {
            throw new IllegalArgumentException(
                    "Mistake-library cursor problem id is invalid");
        }
        this.changeVersion = changeVersion;
        this.learnerCanonicalFingerprint = learnerCanonicalFingerprint;
        this.queryCanonicalFingerprint = queryCanonicalFingerprint;
        this.changedAtEpochMillis = changedAtEpochMillis;
        this.problemId = problemId;
    }

    static StudentMistakeLibraryCursor create(
            long changeVersion,
            String learnerCanonicalFingerprint,
            String queryCanonicalFingerprint,
            long changedAtEpochMillis,
            String problemId) {
        return new StudentMistakeLibraryCursor(
                changeVersion,
                learnerCanonicalFingerprint,
                queryCanonicalFingerprint,
                changedAtEpochMillis,
                problemId);
    }

    long getChangeVersion() {
        return changeVersion;
    }

    String getLearnerCanonicalFingerprint() {
        return learnerCanonicalFingerprint;
    }

    String getQueryCanonicalFingerprint() {
        return queryCanonicalFingerprint;
    }

    long getChangedAtEpochMillis() {
        return changedAtEpochMillis;
    }

    String getProblemId() {
        return problemId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StudentMistakeLibraryCursor)) {
            return false;
        }
        StudentMistakeLibraryCursor cursor = (StudentMistakeLibraryCursor) other;
        return changeVersion == cursor.changeVersion
                && changedAtEpochMillis == cursor.changedAtEpochMillis
                && learnerCanonicalFingerprint.equals(cursor.learnerCanonicalFingerprint)
                && queryCanonicalFingerprint.equals(cursor.queryCanonicalFingerprint)
                && problemId.equals(cursor.problemId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                changeVersion,
                learnerCanonicalFingerprint,
                queryCanonicalFingerprint,
                changedAtEpochMillis,
                problemId);
    }

    @Override
    public String toString() {
        return "StudentMistakeLibraryCursor";
    }
}
