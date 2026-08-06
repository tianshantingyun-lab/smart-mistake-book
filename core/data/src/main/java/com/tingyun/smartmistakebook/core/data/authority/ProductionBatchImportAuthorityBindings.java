package com.tingyun.smartmistakebook.core.data.authority;

import java.util.Objects;

/** Exact process identities that must stay unchanged across batch-import construction. */
final class ProductionBatchImportAuthorityBindings {
    private final Object generation;
    private final Object context;
    private final Object learner;
    private final Object sessionOwner;
    private final Object modelOwner;

    private ProductionBatchImportAuthorityBindings(
            Object generation,
            Object context,
            Object learner,
            Object sessionOwner,
            Object modelOwner) {
        this.generation = Objects.requireNonNull(generation, "generationIdentity");
        this.context = Objects.requireNonNull(context, "contextIdentity");
        this.learner = Objects.requireNonNull(learner, "learnerIdentity");
        this.sessionOwner = Objects.requireNonNull(sessionOwner, "sessionOwnerIdentity");
        this.modelOwner = Objects.requireNonNull(modelOwner, "modelOwnerIdentity");
    }

    static ProductionBatchImportAuthorityBindings of(
            Object generation,
            Object context,
            Object learner,
            Object sessionOwner,
            Object modelOwner) {
        return new ProductionBatchImportAuthorityBindings(
                generation, context, learner, sessionOwner, modelOwner);
    }

    Object generation() {
        return generation;
    }

    Object context() {
        return context;
    }

    Object learner() {
        return learner;
    }

    Object sessionOwner() {
        return sessionOwner;
    }

    Object modelOwner() {
        return modelOwner;
    }

    boolean hasSameIdentities(ProductionBatchImportAuthorityBindings other) {
        return other != null
                && generation == other.generation
                && context == other.context
                && learner == other.learner
                && sessionOwner == other.sessionOwner
                && modelOwner == other.modelOwner;
    }
}
