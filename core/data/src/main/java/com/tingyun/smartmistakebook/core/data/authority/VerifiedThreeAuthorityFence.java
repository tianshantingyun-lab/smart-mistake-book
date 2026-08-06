package com.tingyun.smartmistakebook.core.data.authority;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.IdentityHashMap;
import java.util.regex.Pattern;

/**
 * Process-local proof that all three learning authorities were recovered under the terminal
 * lifecycle exclusion.
 *
 * <p>The private constructor is only half of the boundary: every usable instance must also be
 * present in {@link Owner}'s identity registry. Reflectively constructing this class therefore
 * cannot create a proof accepted by Ready or the current-generation binder.
 */
final class VerifiedThreeAuthorityFence {
    private final long cutoverGeneration;
    private final String proofFingerprint;

    private VerifiedThreeAuthorityFence(long cutoverGeneration, String proofFingerprint) {
        this.cutoverGeneration = cutoverGeneration;
        this.proofFingerprint = proofFingerprint;
    }

    long getCutoverGeneration() {
        return cutoverGeneration;
    }

    String getProofFingerprint() {
        return proofFingerprint;
    }

    /** One-shot capability opened only while the terminal lifecycle exclusion is held. */
    static final class RecoveryPermit implements AutoCloseable {
        private RecoveryPermit() {}

        @Override
        public void close() {
            Owner.revoke(this);
        }
    }

    /** Field snapshot returned only after a registered proof identity has been consumed. */
    static final class Claim {
        private final long cutoverGeneration;
        private final String proofFingerprint;

        private Claim(long cutoverGeneration, String proofFingerprint) {
            this.cutoverGeneration = cutoverGeneration;
            this.proofFingerprint = proofFingerprint;
        }

        long getCutoverGeneration() {
            return cutoverGeneration;
        }

        String getProofFingerprint() {
            return proofFingerprint;
        }
    }

    /** Package-owned issuer and one-shot identity registry. No public or synthetic issuer exists. */
    static final class Owner {
        private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
        private static final IdentityHashMap<RecoveryPermit, Boolean> RECOVERY_PERMITS =
                new IdentityHashMap<>();
        private static final IdentityHashMap<VerifiedThreeAuthorityFence, State> LIVE_PROOFS =
                new IdentityHashMap<>();

        private Owner() {}

        static synchronized RecoveryPermit beginAuditedRecovery() {
            RecoveryPermit permit = new RecoveryPermit();
            if (RECOVERY_PERMITS.put(permit, Boolean.TRUE) != null) {
                throw new IllegalStateException("Recovery permit was already registered");
            }
            return permit;
        }

        static synchronized VerifiedThreeAuthorityFence issueAfterAuditedRecovery(
                RecoveryPermit permit,
                long cutoverGeneration,
                String proofFingerprint,
                KnowledgeActivationWitness knowledge) {
            if (RECOVERY_PERMITS.remove(permit) == null) {
                throw new IllegalStateException(
                        "Recovery permit is forged, revoked, or already consumed");
            }
            requireValidFields(cutoverGeneration, proofFingerprint);
            if (knowledge == null || !knowledge.hasValidFingerprint()) {
                throw new IllegalArgumentException(
                        "Verified fence requires a valid knowledge activation");
            }
            VerifiedThreeAuthorityFence proof =
                    new VerifiedThreeAuthorityFence(cutoverGeneration, proofFingerprint);
            if (LIVE_PROOFS.put(
                            proof,
                            new State(
                                    cutoverGeneration,
                                    proofFingerprint,
                                    knowledge.getWitnessFingerprint()))
                    != null) {
                throw new IllegalStateException("Verified fence was already registered");
            }
            return proof;
        }

        static synchronized void requireLive(VerifiedThreeAuthorityFence proof) {
            State state = LIVE_PROOFS.get(proof);
            if (state == null || !state.matches(proof)) {
                throw new IllegalStateException(
                        "Verified fence is forged, revoked, or already consumed");
            }
        }

        static synchronized void requireMatchesKnowledge(
                VerifiedThreeAuthorityFence proof, KnowledgeActivationWitness knowledge) {
            State state = LIVE_PROOFS.get(proof);
            if (state == null || !state.matches(proof)) {
                throw new IllegalStateException(
                        "Verified fence is forged, revoked, or already consumed");
            }
            if (knowledge == null
                    || !knowledge.hasValidFingerprint()
                    || !MessageDigest.isEqual(
                            state.knowledgeWitnessFingerprint.getBytes(StandardCharsets.US_ASCII),
                            knowledge.getWitnessFingerprint().getBytes(StandardCharsets.US_ASCII))) {
                throw new IllegalStateException(
                        "Verified fence belongs to another knowledge activation");
            }
        }

        static synchronized Claim claim(VerifiedThreeAuthorityFence proof) {
            State state = LIVE_PROOFS.remove(proof);
            if (state == null || !state.matches(proof)) {
                throw new IllegalStateException(
                        "Verified fence is forged, revoked, or already consumed");
            }
            return new Claim(state.cutoverGeneration, state.proofFingerprint);
        }

        static synchronized void revoke(VerifiedThreeAuthorityFence proof) {
            LIVE_PROOFS.remove(proof);
        }

        private static synchronized void revoke(RecoveryPermit permit) {
            RECOVERY_PERMITS.remove(permit);
        }

        private static void requireValidFields(long generation, String fingerprint) {
            if (generation <= 0L) {
                throw new IllegalArgumentException("Cutover generation must be positive");
            }
            if (fingerprint == null || !SHA_256.matcher(fingerprint).matches()) {
                throw new IllegalArgumentException("Proof fingerprint must be canonical SHA-256");
            }
        }

        private static final class State {
            private final long cutoverGeneration;
            private final String proofFingerprint;
            private final String knowledgeWitnessFingerprint;

            private State(
                    long cutoverGeneration,
                    String proofFingerprint,
                    String knowledgeWitnessFingerprint) {
                this.cutoverGeneration = cutoverGeneration;
                this.proofFingerprint = proofFingerprint;
                this.knowledgeWitnessFingerprint = knowledgeWitnessFingerprint;
            }

            private boolean matches(VerifiedThreeAuthorityFence proof) {
                return cutoverGeneration == proof.cutoverGeneration
                        && proofFingerprint.equals(proof.proofFingerprint);
            }
        }
    }
}
