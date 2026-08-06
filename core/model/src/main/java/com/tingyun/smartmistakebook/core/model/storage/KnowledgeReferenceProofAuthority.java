package com.tingyun.smartmistakebook.core.model.storage;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Process-local authority for proofs that a knowledge reference was present in one activated
 * catalog.
 *
 * <p>The authority is created and retained by the local authority runtime. Only its issuer is
 * handed to the knowledge catalog and only its verifier is handed to student/mastery stores.
 * Matching public proof fields are insufficient: verification also requires the same private
 * in-process seal.
 */
public final class KnowledgeReferenceProofAuthority {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private final Object authoritySeal = new Object();
    private final Issuer issuer = new Issuer(authoritySeal);
    private final Verifier verifier = new Verifier(authoritySeal);

    private KnowledgeReferenceProofAuthority() {}

    public static KnowledgeReferenceProofAuthority create() {
        return new KnowledgeReferenceProofAuthority();
    }

    public Issuer getIssuer() {
        return issuer;
    }

    public Verifier getVerifier() {
        return verifier;
    }

    /** Knowledge-owner capability. It is never exposed through model or feature APIs. */
    public static final class Issuer {
        private final Object authoritySeal;

        private Issuer(Object authoritySeal) {
            this.authoritySeal = authoritySeal;
        }

        public Proof issue(
                KnowledgeNodeRef ref,
                String manifestFingerprint,
                long activationGeneration) {
            return new Proof(
                    ref,
                    manifestFingerprint,
                    activationGeneration,
                    authoritySeal);
        }
    }

    /** Consumer capability bound to exactly one process-local knowledge proof authority. */
    public static final class Verifier {
        private final Object authoritySeal;

        private Verifier(Object authoritySeal) {
            this.authoritySeal = authoritySeal;
        }

        public boolean verifies(Proof proof) {
            return proof != null && proof.authoritySeal == authoritySeal;
        }
    }

    /**
     * Minimal cross-authority proof.
     *
     * <p>It contains no knowledge body, DAO, database handle, or copy/rewriting API.
     */
    public static final class Proof {
        private final KnowledgeNodeRef ref;
        private final String manifestFingerprint;
        private final long activationGeneration;
        private final Object authoritySeal;

        private Proof(
                KnowledgeNodeRef ref,
                String manifestFingerprint,
                long activationGeneration,
                Object authoritySeal) {
            this.ref = Objects.requireNonNull(ref, "Knowledge reference is required");
            this.manifestFingerprint =
                    Objects.requireNonNull(
                            manifestFingerprint,
                            "Knowledge manifest fingerprint is required");
            if (!SHA_256.matcher(manifestFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "Knowledge manifest fingerprint must be lowercase SHA-256");
            }
            if (activationGeneration <= 0L) {
                throw new IllegalArgumentException(
                        "Knowledge activation generation must be positive");
            }
            this.activationGeneration = activationGeneration;
            this.authoritySeal = Objects.requireNonNull(authoritySeal, "Authority seal is required");
        }

        public KnowledgeNodeRef getRef() {
            return ref;
        }

        public String getManifestFingerprint() {
            return manifestFingerprint;
        }

        public long getActivationGeneration() {
            return activationGeneration;
        }
    }
}
