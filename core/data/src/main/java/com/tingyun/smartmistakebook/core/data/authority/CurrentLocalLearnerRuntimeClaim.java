package com.tingyun.smartmistakebook.core.data.authority;

import android.content.Context;
import com.tingyun.smartmistakebook.core.model.LocalLearnerKt;
import java.util.IdentityHashMap;
import java.util.Objects;

/** Opaque, one-shot ownership transfer for the canonical local learner runtime. */
final class CurrentLocalLearnerRuntimeClaim implements AutoCloseable {
    private CurrentLocalLearnerRuntimeClaim() {}

    @Override
    public void close() {
        State state = Owner.revoke(this);
        if (state != null) {
            state.runtime.close();
        }
    }

    static final class State {
        private final LocalLearningAuthorityRuntime runtime;
        private final Context canonicalContext;
        private final String learnerId;

        private State(
                LocalLearningAuthorityRuntime runtime,
                Context canonicalContext,
                String learnerId) {
            this.runtime = runtime;
            this.canonicalContext = canonicalContext;
            this.learnerId = learnerId;
        }

        LocalLearningAuthorityRuntime getRuntime() {
            return runtime;
        }

        Context getCanonicalContext() {
            return canonicalContext;
        }

        String getLearnerId() {
            return learnerId;
        }
    }

    /** Package-private owner: no public factory accepts a learner id or a raw runtime. */
    static final class Owner {
        private static final IdentityHashMap<CurrentLocalLearnerRuntimeClaim, State> CLAIMS =
                new IdentityHashMap<>();

        private Owner() {}

        static synchronized CurrentLocalLearnerRuntimeClaim issue(
                LocalLearningAuthorityRuntime runtime,
                Context canonicalContext) {
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(canonicalContext, "canonicalContext");
            CurrentLocalLearnerRuntimeClaim claim = new CurrentLocalLearnerRuntimeClaim();
            if (CLAIMS.put(
                            claim,
                            new State(
                                    runtime,
                                    canonicalContext,
                                    LocalLearnerKt.LOCAL_LEARNER_ID))
                    != null) {
                throw new IllegalStateException("Runtime claim was already registered");
            }
            return claim;
        }

        static synchronized State claim(CurrentLocalLearnerRuntimeClaim claim) {
            State state = CLAIMS.remove(claim);
            if (state == null) {
                throw new IllegalStateException(
                        "Runtime claim is forged, revoked, or already consumed");
            }
            return state;
        }

        static synchronized State revoke(CurrentLocalLearnerRuntimeClaim claim) {
            return CLAIMS.remove(claim);
        }
    }
}
