package com.tingyun.smartmistakebook.core.mastery.database;

/**
 * Source-issued observation wrapper.
 *
 * <p>A real Java private constructor prevents Kotlin's synthetic default-argument constructor from
 * turning untrusted facts into a caller-forgeable trusted command.
 */
final class AuthorityIssuedLearningObservation {
    private final LearningObservationFacts facts;
    private final Object issuerSeal;

    private AuthorityIssuedLearningObservation(
            LearningObservationFacts facts,
            Object issuerSeal) {
        this.facts = facts;
        this.issuerSeal = issuerSeal;
    }

    static AuthorityIssuedLearningObservation issue(
            LearningObservationFacts facts,
            Object issuerSeal) {
        return new AuthorityIssuedLearningObservation(facts, issuerSeal);
    }

    LearningObservationFacts getFacts() {
        return facts;
    }

    void requireIssuedBy(Object expectedSeal) {
        if (issuerSeal != expectedSeal) {
            throw new IllegalStateException(
                    "Learning observation was not issued by this learner-mastery runtime");
        }
    }
}
