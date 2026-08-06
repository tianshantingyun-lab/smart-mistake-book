package com.tingyun.smartmistakebook.core.mastery.database;

/**
 * One-shot runtime-generation capability used only to open the learner-bound weak-candidate
 * owner.
 *
 * <p>There is no public constructor, factory, getter, generation, seal, or fingerprint surface.
 * Identity and learner binding live exclusively in the database issuer registry.
 */
public final class LearnerMasteryOpenResponseOwnerGrant {
    private final Object issuerSeal;

    LearnerMasteryOpenResponseOwnerGrant(Object issuerSeal) {
        CoreDataLearnerMasteryOwnerBridge.requireOpenResponseOwnerSeal(issuerSeal);
        this.issuerSeal = issuerSeal;
    }

    Object issuerSealForDatabase() {
        return issuerSeal;
    }
}
