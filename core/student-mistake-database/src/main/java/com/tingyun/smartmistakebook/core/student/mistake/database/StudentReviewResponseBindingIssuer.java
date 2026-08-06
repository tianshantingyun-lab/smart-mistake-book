package com.tingyun.smartmistakebook.core.student.mistake.database;

import java.util.Objects;

/** Owner-only response binding; neither the signer nor its dynamic issue method is public JVM ABI. */
abstract class StudentReviewResponseBindingIssuer {
    abstract String algorithmVersion();

    abstract String issue(String scopeCanonicalFingerprint, String canonicalResponse);
}

/** Uses the generation- and relay-epoch-bound student outbox key under a separate MAC domain. */
final class StudentOutboxBoundReviewResponseBindingIssuer
        extends StudentReviewResponseBindingIssuer {
    static final String ALGORITHM_VERSION =
            "student-outbox-keyed-review-response-hmac-sha256-v2";

    private final StudentOutboxAuthenticator authenticator;

    StudentOutboxBoundReviewResponseBindingIssuer(StudentOutboxAuthenticator authenticator) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
    }

    @Override
    String algorithmVersion() {
        return ALGORITHM_VERSION;
    }

    @Override
    String issue(String scopeCanonicalFingerprint, String canonicalResponse) {
        return authenticator.issueReviewResponseBinding(
                scopeCanonicalFingerprint, canonicalResponse);
    }
}
