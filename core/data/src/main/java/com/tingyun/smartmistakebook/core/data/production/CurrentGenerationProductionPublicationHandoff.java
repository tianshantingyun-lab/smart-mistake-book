package com.tingyun.smartmistakebook.core.data.production;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Package-owned, single-use handoff from the current-generation authority owner to publication.
 * No proof bytes, generation number, learner id, or database handle are carried here.
 */
final class CurrentGenerationProductionPublicationHandoff implements AutoCloseable {
    private final AtomicReference<State> state;

    CurrentGenerationProductionPublicationHandoff(
            IssuedProductionCapabilityLeases leases,
            Object authorityBinding) {
        state =
                new AtomicReference<>(
                        new State(
                                Objects.requireNonNull(leases, "leases"),
                                Objects.requireNonNull(authorityBinding, "authorityBinding")));
    }

    State claim() {
        final State claimed = state.getAndSet(null);
        if (claimed == null) {
            throw new IllegalStateException(
                    "Current-generation publication handoff is revoked or already claimed");
        }
        return claimed;
    }

    @Override
    public void close() {
        final State revoked = state.getAndSet(null);
        if (revoked != null) {
            revoked.leases.closeReverse();
        }
    }

    static final class State {
        final IssuedProductionCapabilityLeases leases;
        final Object authorityBinding;

        private State(
                IssuedProductionCapabilityLeases leases,
                Object authorityBinding) {
            this.leases = leases;
            this.authorityBinding = authorityBinding;
        }
    }
}
