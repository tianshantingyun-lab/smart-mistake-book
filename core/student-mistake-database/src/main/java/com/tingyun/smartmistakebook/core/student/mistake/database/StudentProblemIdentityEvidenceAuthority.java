package com.tingyun.smartmistakebook.core.student.mistake.database;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Process-local seal for identity receipts persisted and verified by the student-mistake owner.
 *
 * <p>Receipt rows are durable audit facts. A usable reference or proof additionally carries this
 * runtime's private seal, so copying receipt ids or fingerprints cannot mint authority.
 */
final class StudentProblemIdentityEvidenceAuthority {
    private final Object authoritySeal = new Object();
    private final Issuer issuer;
    private final Verifier verifier = new Verifier(authoritySeal);

    private StudentProblemIdentityEvidenceAuthority(
            String issuerKeyId,
            String issuerVersion) {
        this.issuer = new Issuer(
                requireText(issuerKeyId, "Identity issuer key id"),
                requireText(issuerVersion, "Identity issuer version"),
                authoritySeal);
    }

    static StudentProblemIdentityEvidenceAuthority create(
            String issuerKeyId,
            String issuerVersion) {
        return new StudentProblemIdentityEvidenceAuthority(issuerKeyId, issuerVersion);
    }

    Issuer getIssuer() {
        return issuer;
    }

    Verifier getVerifier() {
        return verifier;
    }

    /** Opaque owner-issued pointer to one immutable receipt-ledger row. */
    static final class ReceiptReference {
        private final String receiptId;
        private final String receiptCanonicalFingerprint;
        private final StudentProblemIdentityReceiptKind receiptKind;
        private final Object authoritySeal;

        private ReceiptReference(
                String receiptId,
                String receiptCanonicalFingerprint,
                StudentProblemIdentityReceiptKind receiptKind,
                Object authoritySeal) {
            this.receiptId = requireText(receiptId, "Identity receipt id");
            this.receiptCanonicalFingerprint = requireSha256(
                    receiptCanonicalFingerprint,
                    "Identity receipt fingerprint");
            this.receiptKind = Objects.requireNonNull(
                    receiptKind,
                    "Identity receipt kind is required");
            this.authoritySeal = Objects.requireNonNull(
                    authoritySeal,
                    "Identity receipt authority seal is required");
        }

        public String getReceiptId() {
            return receiptId;
        }

        public String getReceiptCanonicalFingerprint() {
            return receiptCanonicalFingerprint;
        }

        StudentProblemIdentityReceiptKind getReceiptKind() {
            return receiptKind;
        }

        @Override
        public String toString() {
            return "ReceiptReference(receiptId=" + receiptId
                    + ", receiptKind=" + receiptKind
                    + ", receiptCanonicalFingerprint=***)";
        }
    }

    /**
     * Package-private evidence created only after this owner verifies a trusted-source receipt.
     *
     * <p>The public capture command accepts the marker interface, but no caller outside this
     * package can name or construct this privileged implementation.
     */
    static final class TrustedSourceEvidence implements StudentProblemIdentityEvidence {
        private final TrustedSourceProof proof;

        TrustedSourceEvidence(TrustedSourceProof proof) {
            this.proof = Objects.requireNonNull(
                    proof,
                    "Trusted-source identity proof is required");
        }

        TrustedSourceProof getProof() {
            return proof;
        }
    }

    /**
     * Package-private evidence created only after this owner verifies a reviewed-alias receipt.
     */
    static final class ReviewedAliasEvidence implements StudentProblemIdentityEvidence {
        private final ReviewedAliasProof proof;

        ReviewedAliasEvidence(ReviewedAliasProof proof) {
            this.proof = Objects.requireNonNull(
                    proof,
                    "Reviewed-alias identity proof is required");
        }

        ReviewedAliasProof getProof() {
            return proof;
        }
    }

    /** Owner-only capability retained inside the student-mistake runtime. */
    static final class Issuer {
        private final String issuerKeyId;
        private final String issuerVersion;
        private final Object authoritySeal;

        private Issuer(
                String issuerKeyId,
                String issuerVersion,
                Object authoritySeal) {
            this.issuerKeyId = issuerKeyId;
            this.issuerVersion = issuerVersion;
            this.authoritySeal = authoritySeal;
        }

        String getIssuerKeyId() {
            return issuerKeyId;
        }

        String getIssuerVersion() {
            return issuerVersion;
        }

        ReceiptReference issueReference(StudentProblemIdentityReceiptEntity receipt) {
            requireOwnerReceipt(receipt);
            return new ReceiptReference(
                    receipt.getReceiptId(),
                    receipt.getReceiptCanonicalFingerprint(),
                    StudentProblemIdentityReceiptKind.valueOf(receipt.getReceiptKind()),
                    authoritySeal);
        }

        TrustedSourceProof issueTrustedSource(StudentProblemIdentityReceiptEntity receipt) {
            requireOwnerReceipt(receipt);
            if (!StudentProblemIdentityReceiptKind.TRUSTED_SOURCE.name()
                    .equals(receipt.getReceiptKind())) {
                throw new IllegalArgumentException(
                        "Trusted-source proof requires a trusted-source receipt");
            }
            return new TrustedSourceProof(receipt, authoritySeal);
        }

        ReviewedAliasProof issueReviewedAlias(StudentProblemIdentityReceiptEntity receipt) {
            requireOwnerReceipt(receipt);
            if (!StudentProblemIdentityReceiptKind.REVIEWED_ALIAS.name()
                    .equals(receipt.getReceiptKind())) {
                throw new IllegalArgumentException(
                        "Reviewed-alias proof requires a reviewed-alias receipt");
            }
            return new ReviewedAliasProof(receipt, authoritySeal);
        }

        private void requireOwnerReceipt(StudentProblemIdentityReceiptEntity receipt) {
            Objects.requireNonNull(receipt, "Identity receipt is required");
            if (!issuerKeyId.equals(receipt.getIssuerKeyId())
                    || !issuerVersion.equals(receipt.getIssuerVersion())) {
                throw new IllegalArgumentException(
                        "Identity receipt belongs to another owner issuer");
            }
        }
    }

    /** Store-side capability bound to the same private owner seal. */
    static final class Verifier {
        private final Object authoritySeal;

        private Verifier(Object authoritySeal) {
            this.authoritySeal = authoritySeal;
        }

        public boolean verifies(ReceiptReference reference) {
            return reference != null && reference.authoritySeal == authoritySeal;
        }

        public boolean verifies(TrustedSourceProof proof) {
            return proof != null && proof.authoritySeal == authoritySeal;
        }

        public boolean verifies(ReviewedAliasProof proof) {
            return proof != null && proof.authoritySeal == authoritySeal;
        }
    }

    /** Opaque proof for one owner-verified durable source locator. */
    public static final class TrustedSourceProof {
        private final StudentProblemIdentityReceiptEntity receipt;
        private final String proofCanonicalFingerprint;
        private final Object authoritySeal;

        private TrustedSourceProof(
                StudentProblemIdentityReceiptEntity receipt,
                Object authoritySeal) {
            this.receipt = receipt;
            requirePresent(receipt.getLocatorNamespace(), "Trusted-source locator namespace");
            requirePresent(receipt.getLocatorVersion(), "Trusted-source locator version");
            requirePresent(
                    receipt.getItemLocatorCanonicalFingerprint(),
                    "Trusted-source item locator fingerprint");
            this.authoritySeal = Objects.requireNonNull(authoritySeal, "Authority seal is required");
            this.proofCanonicalFingerprint = proofFingerprint(
                    "student-problem-trusted-source-proof-v2",
                    receipt);
        }

        public String getReceiptId() { return receipt.getReceiptId(); }
        public String getReceiptCanonicalFingerprint() {
            return receipt.getReceiptCanonicalFingerprint();
        }
        public String getIssuerKeyId() { return receipt.getIssuerKeyId(); }
        public String getIssuerVersion() { return receipt.getIssuerVersion(); }
        public String getLearnerId() { return receipt.getLearnerId(); }
        public String getSubject() { return receipt.getSubject(); }
        public String getSourceKind() { return receipt.getSourceKind(); }
        public String getSourceIntentId() { return receipt.getSourceIntentId(); }
        public String getIdempotencyKey() { return receipt.getIdempotencyKey(); }
        public String getSourceCanonicalFingerprint() {
            return receipt.getSourceCanonicalFingerprint();
        }
        public String getLocatorNamespace() { return receipt.getLocatorNamespace(); }
        public String getLocatorVersion() { return receipt.getLocatorVersion(); }
        public String getItemLocatorCanonicalFingerprint() {
            return receipt.getItemLocatorCanonicalFingerprint();
        }
        public String getProblemId() { return receipt.getProblemId(); }
        public String getPracticeUnitId() { return receipt.getPracticeUnitId(); }
        public String getRevisionId() { return receipt.getRevisionId(); }
        public int getRevisionNumber() { return receipt.getRevisionNumber(); }
        public String getCandidateDocumentCanonicalFingerprint() {
            return receipt.getDocumentCanonicalFingerprint();
        }
        public String getTargetCanonicalFingerprint() {
            return receipt.getTargetCanonicalFingerprint();
        }
        public String getAssetManifestCanonicalFingerprint() {
            return receipt.getAssetManifestCanonicalFingerprint();
        }
        public String getSelectedRegionCanonicalFingerprint() {
            return receipt.getSelectedRegionCanonicalFingerprint();
        }
        public String getCandidateCanonicalFingerprint() {
            return receipt.getCandidateCanonicalFingerprint();
        }
        public long getIssuedAtEpochMillis() { return receipt.getIssuedAtEpochMillis(); }
        public long getExpiresAtEpochMillis() { return receipt.getExpiresAtEpochMillis(); }
        public String getProofCanonicalFingerprint() { return proofCanonicalFingerprint; }
    }

    /** Opaque proof that an authenticated review linked the candidate to an existing identity. */
    public static final class ReviewedAliasProof {
        private final StudentProblemIdentityReceiptEntity receipt;
        private final String proofCanonicalFingerprint;
        private final Object authoritySeal;

        private ReviewedAliasProof(
                StudentProblemIdentityReceiptEntity receipt,
                Object authoritySeal) {
            this.receipt = receipt;
            requirePresent(receipt.getReviewAuthorityKind(), "Alias review authority");
            StudentProblemAliasReviewAuthorityKind.valueOf(receipt.getReviewAuthorityKind());
            requirePresent(receipt.getExistingIdentityNamespace(), "Alias identity namespace");
            requirePresent(receipt.getExistingIdentityVersion(), "Alias identity version");
            requirePresent(receipt.getExistingIdentityStableKey(), "Alias identity stable key");
            requirePresent(
                    receipt.getExistingIdentityCanonicalFingerprint(),
                    "Alias identity fingerprint");
            requirePresent(receipt.getReviewCaseId(), "Alias review case id");
            if (receipt.getReviewRevision() == null || receipt.getReviewRevision() <= 0) {
                throw new IllegalArgumentException("Alias review revision must be positive");
            }
            requirePresent(
                    receipt.getReviewDecisionCanonicalFingerprint(),
                    "Alias review decision fingerprint");
            this.authoritySeal = Objects.requireNonNull(authoritySeal, "Authority seal is required");
            this.proofCanonicalFingerprint = proofFingerprint(
                    "student-problem-reviewed-alias-proof-v2",
                    receipt);
        }

        public String getReceiptId() { return receipt.getReceiptId(); }
        public String getReceiptCanonicalFingerprint() {
            return receipt.getReceiptCanonicalFingerprint();
        }
        public String getIssuerKeyId() { return receipt.getIssuerKeyId(); }
        public String getIssuerVersion() { return receipt.getIssuerVersion(); }
        public String getLearnerId() { return receipt.getLearnerId(); }
        public String getSubject() { return receipt.getSubject(); }
        public String getSourceKind() { return receipt.getSourceKind(); }
        public String getSourceIntentId() { return receipt.getSourceIntentId(); }
        public String getIdempotencyKey() { return receipt.getIdempotencyKey(); }
        public String getSourceCanonicalFingerprint() {
            return receipt.getSourceCanonicalFingerprint();
        }
        public String getProblemId() { return receipt.getProblemId(); }
        public String getPracticeUnitId() { return receipt.getPracticeUnitId(); }
        public String getRevisionId() { return receipt.getRevisionId(); }
        public int getRevisionNumber() { return receipt.getRevisionNumber(); }
        public String getCandidateDocumentCanonicalFingerprint() {
            return receipt.getDocumentCanonicalFingerprint();
        }
        public String getTargetCanonicalFingerprint() {
            return receipt.getTargetCanonicalFingerprint();
        }
        public String getCandidateAssetManifestCanonicalFingerprint() {
            return receipt.getAssetManifestCanonicalFingerprint();
        }
        public String getCandidateSelectedRegionCanonicalFingerprint() {
            return receipt.getSelectedRegionCanonicalFingerprint();
        }
        public String getCandidateCanonicalFingerprint() {
            return receipt.getCandidateCanonicalFingerprint();
        }
        public String getReviewAuthorityKind() { return receipt.getReviewAuthorityKind(); }
        public String getExistingIdentityNamespace() {
            return receipt.getExistingIdentityNamespace();
        }
        public String getExistingIdentityVersion() {
            return receipt.getExistingIdentityVersion();
        }
        public String getExistingIdentityStableKey() {
            return receipt.getExistingIdentityStableKey();
        }
        public String getExistingIdentityCanonicalFingerprint() {
            return receipt.getExistingIdentityCanonicalFingerprint();
        }
        public String getReviewCaseId() { return receipt.getReviewCaseId(); }
        public int getReviewRevision() { return receipt.getReviewRevision(); }
        public String getReviewDecisionCanonicalFingerprint() {
            return receipt.getReviewDecisionCanonicalFingerprint();
        }
        public long getIssuedAtEpochMillis() { return receipt.getIssuedAtEpochMillis(); }
        public long getExpiresAtEpochMillis() { return receipt.getExpiresAtEpochMillis(); }
        public String getProofCanonicalFingerprint() { return proofCanonicalFingerprint; }
    }

    private static String proofFingerprint(
            String domain,
            StudentProblemIdentityReceiptEntity receipt) {
        return canonicalFingerprint(
                domain,
                receipt.getReceiptId(),
                receipt.getReceiptKind(),
                receipt.getReceiptCanonicalFingerprint(),
                Integer.toString(receipt.getFingerprintVersion()),
                receipt.getIssuerKeyId(),
                receipt.getIssuerVersion(),
                receipt.getLearnerId(),
                receipt.getSubject(),
                receipt.getSourceKind(),
                receipt.getSourceIntentId(),
                receipt.getIdempotencyKey(),
                receipt.getSourceCanonicalFingerprint(),
                nullToEmpty(receipt.getLocatorNamespace()),
                nullToEmpty(receipt.getLocatorVersion()),
                nullToEmpty(receipt.getItemLocatorCanonicalFingerprint()),
                receipt.getProblemId(),
                receipt.getPracticeUnitId(),
                receipt.getRevisionId(),
                Integer.toString(receipt.getRevisionNumber()),
                receipt.getDocumentCanonicalFingerprint(),
                receipt.getTargetCanonicalFingerprint(),
                receipt.getAssetManifestCanonicalFingerprint(),
                receipt.getSelectedRegionCanonicalFingerprint(),
                receipt.getCandidateCanonicalFingerprint(),
                nullToEmpty(receipt.getReviewAuthorityKind()),
                nullToEmpty(receipt.getExistingIdentityNamespace()),
                nullToEmpty(receipt.getExistingIdentityVersion()),
                nullToEmpty(receipt.getExistingIdentityStableKey()),
                nullToEmpty(receipt.getExistingIdentityCanonicalFingerprint()),
                nullToEmpty(receipt.getReviewCaseId()),
                receipt.getReviewRevision() == null
                        ? ""
                        : Integer.toString(receipt.getReviewRevision()),
                nullToEmpty(receipt.getReviewDecisionCanonicalFingerprint()),
                Integer.toString(receipt.getRenewalGeneration()),
                Long.toString(receipt.getIssuedAtEpochMillis()),
                Long.toString(receipt.getExpiresAtEpochMillis()));
    }

    private static String canonicalFingerprint(String domain, String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateField(digest, domain);
            for (String field : fields) {
                updateField(digest, field);
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void updateField(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] length = Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII);
        digest.update(length);
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) ';');
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0xf, 16));
            result.append(Character.forDigit(value & 0xf, 16));
        }
        return result.toString();
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label + " is required");
        if (value.isBlank()
                || !value.equals(value.trim())
                || value.length() > 256
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    label + " must be trimmed, bounded, and non-blank");
        }
        return value;
    }

    private static String requireSha256(String value, String label) {
        Objects.requireNonNull(value, label + " is required");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " must be lowercase SHA-256");
        }
        return value;
    }

    private static String requirePresent(String value, String label) {
        return requireText(value, label);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
