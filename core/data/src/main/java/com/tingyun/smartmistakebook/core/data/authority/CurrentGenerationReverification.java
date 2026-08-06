package com.tingyun.smartmistakebook.core.data.authority;

import android.content.Context;
import com.tingyun.smartmistakebook.core.model.LocalLearnerKt;
import java.util.IdentityHashMap;
import java.util.Objects;

/** Opaque result issued only by the audited current-authority recovery path. */
final class CurrentGenerationReverification implements AutoCloseable {
    private CurrentGenerationReverification() {}

    @Override
    public void close() {
        State state = Owner.revoke(this);
        if (state != null) {
            VerifiedThreeAuthorityFence.Owner.revoke(state.proof);
        }
    }

    static final class State {
        private final Context canonicalContext;
        private final String learnerId;
        private final VerifiedThreeAuthorityFence proof;
        private final KnowledgeActivationWitness knowledge;

        private State(
                Context canonicalContext,
                VerifiedThreeAuthorityFence proof,
                KnowledgeActivationWitness knowledge) {
            this.canonicalContext = canonicalContext;
            this.learnerId = LocalLearnerKt.LOCAL_LEARNER_ID;
            this.proof = proof;
            this.knowledge = knowledge;
        }

        String getLearnerId() {
            return learnerId;
        }

        VerifiedThreeAuthorityFence getProof() {
            return proof;
        }

        KnowledgeActivationWitness getKnowledge() {
            return knowledge;
        }
    }

    /** Package-private owner; there is no public synthetic issuer accepting raw scope fields. */
    static final class Owner {
        private static final IdentityHashMap<CurrentGenerationReverification, State> CLAIMS =
                new IdentityHashMap<>();

        private Owner() {}

        static synchronized CurrentGenerationReverification issueFromAuditedOwners(
                Context canonicalContext,
                VerifiedThreeAuthorityFence proof,
                KnowledgeActivationWitness knowledge) {
            Objects.requireNonNull(canonicalContext, "canonicalContext");
            Objects.requireNonNull(proof, "proof");
            Objects.requireNonNull(knowledge, "knowledge");
            VerifiedThreeAuthorityFence.Owner.requireMatchesKnowledge(proof, knowledge);
            if (!knowledge.hasValidFingerprint()) {
                throw new IllegalArgumentException(
                        "Current generation requires a verified knowledge activation");
            }
            CurrentGenerationReverification claim = new CurrentGenerationReverification();
            if (CLAIMS.put(claim, new State(canonicalContext, proof, knowledge)) != null) {
                throw new IllegalStateException(
                        "Current-generation reverification was already registered");
            }
            return claim;
        }

        static synchronized State claim(
                CurrentGenerationReverification claim, Context canonicalContext) {
            State state = CLAIMS.remove(claim);
            if (state == null) {
                throw new IllegalStateException(
                        "Current-generation reverification is forged, revoked, or already claimed");
            }
            if (state.canonicalContext != canonicalContext) {
                VerifiedThreeAuthorityFence.Owner.revoke(state.proof);
                throw new IllegalStateException(
                        "Current-generation reverification belongs to another application context");
            }
            return state;
        }

        static synchronized State revoke(CurrentGenerationReverification claim) {
            return CLAIMS.remove(claim);
        }
    }
}
