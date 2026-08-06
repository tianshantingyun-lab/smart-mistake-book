package com.tingyun.smartmistakebook.core.student.mistake.database;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import com.tingyun.smartmistakebook.core.model.CanonicalSha256;
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope;
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventPayload;
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingsAcceptedV1;
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingsSnapshotV2;
import com.tingyun.smartmistakebook.core.model.storage.ProblemLifecycleChangedV1;
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionCommittedV1;
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionSupersededV1;
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV1;
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV2;
import com.tingyun.smartmistakebook.core.model.storage.StudentMistakeRelayMessage;
import com.tingyun.smartmistakebook.core.model.storage.StudentOutboxAuthenticityProof;
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

/** Owner-private issuer used only after the real student outbox row has been read. */
abstract class StudentOutboxAuthenticityIssuer {
    abstract StudentMistakeRelayMessage attest(CrossStoreEventEnvelope envelope);

    abstract String relayEpoch();
}

/** A conclusive row-local proof failure; retrying with the same bytes cannot make it valid. */
final class StudentOutboxInvalidProofException extends SecurityException {
    StudentOutboxInvalidProofException(String message) {
        super(message);
    }

    StudentOutboxInvalidProofException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * Learner-bound student-outbox authenticator.
 *
 * <p>This type is deliberately Java package-private. Kotlin {@code internal} is public in JVM
 * bytecode and would expose an accidental signing API to other production modules. The signed APK
 * and its dependencies remain the trusted computing base; this boundary prevents unreviewed
 * production call paths and model/remote data from obtaining a signing capability. It is not
 * presented as a sandbox against arbitrary same-UID executable code or reflective test code.
 */
final class StudentOutboxAuthenticator extends StudentOutboxAuthenticityIssuer {
    static final int KEY_VERSION = 1;
    static final String REJECTION =
            "Student outbox authenticity verification failed";
    private static final Pattern LOWERCASE_SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private final String learnerId;
    private final ActiveStudentOutboxAuthenticityKey activeKey;
    private final StudentOutboxHmacKeyStore keyStore;
    private final StudentOutboxAuthenticityVerifier verifier;

    StudentOutboxAuthenticator(
            String learnerId,
            ActiveStudentOutboxAuthenticityKey activeKey,
            StudentOutboxHmacKeyStore keyStore) {
        requireText(learnerId, "Learner id");
        this.learnerId = learnerId;
        this.activeKey = Objects.requireNonNull(activeKey, "activeKey");
        this.keyStore = Objects.requireNonNull(keyStore, "keyStore");
        if (!StudentOutboxAuthenticityProof.ALGORITHM_VERSION.equals(
                activeKey.getAlgorithmVersion())) {
            throw new IllegalArgumentException(
                    "Unsupported active student-outbox authenticity algorithm");
        }
        this.verifier =
                StudentOutboxAuthenticityVerifier.ownerIssued(
                        new OwnerVerificationOperation(this));
    }

    StudentOutboxAuthenticityVerifier getVerifier() {
        return verifier;
    }

    ActiveStudentOutboxAuthenticityKey activeKeyForOwnerCheck() {
        return activeKey;
    }

    @Override
    StudentMistakeRelayMessage attest(CrossStoreEventEnvelope envelope) {
        requireActiveGeneration(envelope);
        String context =
                authenticatedContextFingerprint(
                        envelope,
                        learnerId,
                        activeKey.getKeyId(),
                        activeKey.getAlgorithmVersion());
        StudentOutboxAuthenticityProof proof =
                new StudentOutboxAuthenticityProof(
                        StudentOutboxAuthenticityProof.PROTOCOL_VERSION,
                        activeKey.getAlgorithmVersion(),
                        activeKey.getKeyId(),
                        learnerId,
                        envelope.getCanonicalFingerprint(),
                        keyStore.issueHmac(activeKey.getKeyAlias(), context));
        return StudentMistakeRelayMessage.fromUnverifiedEnvelopeAndProof(envelope, proof);
    }

    @Override
    String relayEpoch() {
        return activeKey.getRelayEpoch();
    }

    String issueReviewResponseBinding(
            String scopeCanonicalFingerprint,
            String canonicalResponse) {
        requireLowercaseSha256(
                scopeCanonicalFingerprint, "Review response-binding scope fingerprint");
        Objects.requireNonNull(canonicalResponse, "canonicalResponse");
        String responseFingerprint =
                new CanonicalSha256("student-review-canonical-response-v1")
                        .field("canonicalResponse", canonicalResponse)
                        .finish();
        String bindingContext =
                new CanonicalSha256("student-review-response-binding-context-v2")
                        .field("learnerId", learnerId)
                        .field("sourceStoreGeneration", activeKey.getSourceStoreGeneration())
                        .field("relayEpoch", activeKey.getRelayEpoch())
                        .field("issuerKeyId", activeKey.getKeyId())
                        .field("keyAlgorithmVersion", activeKey.getAlgorithmVersion())
                        .field("scopeCanonicalFingerprint", scopeCanonicalFingerprint)
                        .field("responseCanonicalFingerprint", responseFingerprint)
                        .finish();
        return keyStore.issueReviewResponseBinding(activeKey.getKeyAlias(), bindingContext);
    }

    /** Owner-package bridge used only by the bound runtime session. */
    StudentOutboxVerificationReceipt verifyAuthentic(
            StudentMistakeRelayMessage message) {
        CrossStoreEventEnvelope envelope = message.getEnvelope();
        StudentOutboxAuthenticityProof proof = message.getAuthenticityProof();
        String authenticatedContext;
        try {
            requireActiveGeneration(envelope);
            if (proof.getProtocolVersion()
                            != StudentOutboxAuthenticityProof.PROTOCOL_VERSION
                    || !proof.getAlgorithmVersion().equals(activeKey.getAlgorithmVersion())
                    || !proof.getIssuerKeyId().equals(activeKey.getKeyId())
                    || !proof.getLearnerId().equals(learnerId)
                    || !proof.getEnvelopeCanonicalFingerprint()
                            .equals(envelope.getCanonicalFingerprint())) {
                throw new StudentOutboxInvalidProofException(
                        "Student outbox proof scope mismatch");
            }
            authenticatedContext =
                    authenticatedContextFingerprint(
                            envelope,
                            learnerId,
                            activeKey.getKeyId(),
                            activeKey.getAlgorithmVersion());
        } catch (StudentOutboxInvalidProofException failure) {
            throw failure;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new StudentOutboxInvalidProofException(REJECTION, failure);
        }
        String expected =
                keyStore.issueHmac(activeKey.getKeyAlias(), authenticatedContext);
        if (!constantTimeHexEquals(expected, proof.getTagHex())) {
            throw new StudentOutboxInvalidProofException(
                    "Student outbox proof tag mismatch");
        }
        return StudentOutboxVerificationReceipt.ownerIssued(
                message,
                activeKey.getSourceStoreGeneration(),
                activeKey.getRelayEpoch(),
                activeKey.getKeyId(),
                activeKey.getAlgorithmVersion());
    }

    private void requireActiveGeneration(CrossStoreEventEnvelope envelope) {
        requireEnvelopeScope(envelope, learnerId);
        if (!envelope.getSourceStoreGeneration().equals(activeKey.getSourceStoreGeneration())) {
            throw new StudentOutboxInvalidProofException(StudentOutboxAuthenticator.REJECTION);
        }
    }

    static String authenticatedContextFingerprint(
            CrossStoreEventEnvelope envelope,
            String learnerId,
            String issuerKeyId,
            String algorithmVersion) {
        requireEnvelopeScope(envelope, learnerId);
        requireText(issuerKeyId, "Student-outbox authenticity key id");
        if (!StudentOutboxAuthenticityProof.ALGORITHM_VERSION.equals(algorithmVersion)) {
            throw new IllegalArgumentException(
                    "Unsupported student-outbox authenticity algorithm");
        }
        return new CanonicalSha256(
                        StudentOutboxAuthenticityProof.AUTHENTICATED_CONTEXT_DOMAIN)
                .field("proofProtocolVersion", StudentOutboxAuthenticityProof.PROTOCOL_VERSION)
                .field("algorithmVersion", algorithmVersion)
                .field("issuerKeyId", issuerKeyId)
                .field("learnerId", learnerId)
                .field("sourceStore", envelope.getSourceStore().name())
                .field("destinationStore", envelope.getDestinationStore().name())
                .field("sourceStoreGeneration", envelope.getSourceStoreGeneration())
                .field("eventId", envelope.getEventId())
                .field("aggregateId", envelope.getAggregateId())
                .field("aggregateVersion", envelope.getAggregateVersion())
                .field("occurredAtEpochMillis", envelope.getOccurredAtEpochMillis())
                .field("idempotencyKey", envelope.getIdempotencyKey())
                .field("payloadType", envelope.getPayloadType())
                .field("payloadVersion", envelope.getPayloadVersion())
                .field("payloadCanonicalFingerprint", envelope.getPayloadCanonicalFingerprint())
                .field("envelopeCanonicalFingerprint", envelope.getCanonicalFingerprint())
                .finish();
    }

    private static void requireEnvelopeScope(
            CrossStoreEventEnvelope envelope, String learnerId) {
        if (envelope.getSourceStore() != StudyStoreKind.STUDENT_MISTAKES
                || envelope.getDestinationStore() != StudyStoreKind.LEARNER_MASTERY) {
            throw new IllegalArgumentException(
                    "Student-outbox authenticity accepts only the student-to-mastery route");
        }
        CrossStoreEventPayload payload = envelope.getPayload();
        String payloadLearnerId = null;
        if (payload instanceof ProblemRevisionCommittedV1 value) {
            payloadLearnerId = value.getRevision().getProblem().getLearnerId();
        } else if (payload instanceof ProblemKnowledgeBindingsAcceptedV1 value) {
            payloadLearnerId = value.getProblemRevision().getProblem().getLearnerId();
        } else if (payload instanceof ProblemKnowledgeBindingsSnapshotV2 value) {
            payloadLearnerId = value.getProblemRevision().getProblem().getLearnerId();
        } else if (payload instanceof ProblemLifecycleChangedV1 value) {
            payloadLearnerId = value.getProblemRevision().getProblem().getLearnerId();
        } else if (payload instanceof ProblemRevisionSupersededV1 value) {
            payloadLearnerId = value.getPreviousRevision().getProblem().getLearnerId();
        } else if (payload instanceof ReviewObservationCapturedV1 value) {
            payloadLearnerId = value.getProblemRevision().getProblem().getLearnerId();
        } else if (payload instanceof ReviewObservationCapturedV2 value) {
            payloadLearnerId = value.getProblemRevision().getProblem().getLearnerId();
        }
        if (!learnerId.equals(payloadLearnerId)) {
            throw new IllegalArgumentException(
                    "Student-outbox payload is outside the bound learner scope");
        }
    }

    private static boolean constantTimeHexEquals(String expected, String actual) {
        if (!LOWERCASE_SHA_256.matcher(expected).matches()
                || !LOWERCASE_SHA_256.matcher(actual).matches()) {
            return false;
        }
        return MessageDigest.isEqual(hexToBytes(expected), hexToBytes(actual));
    }

    private static byte[] hexToBytes(String value) {
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            int high = Character.digit(value.charAt(index * 2), 16);
            int low = Character.digit(value.charAt(index * 2 + 1), 16);
            result[index] = (byte) ((high << 4) | low);
        }
        return result;
    }

    static void requireLowercaseSha256(String value, String label) {
        if (!LOWERCASE_SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must be a lowercase SHA-256 value");
        }
    }

    private static void requireText(String value, String label) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.trim())
                || value.length() > 256
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    label + " must be a trimmed non-blank value of at most 256 characters");
        }
    }

    private static final class OwnerVerificationOperation
            implements StudentOutboxAuthenticityVerificationOperation {
        private final StudentOutboxAuthenticator owner;

        private OwnerVerificationOperation(StudentOutboxAuthenticator owner) {
            this.owner = owner;
        }

        @Override
        public StudentOutboxVerificationReceipt verify(StudentMistakeRelayMessage message) {
            return owner.verifyAuthentic(message);
        }
    }
}

/** Stable verify-only holder published before the first asynchronous outbox read. */
final class StudentOutboxAuthenticatorSession {
    private final String learnerId;
    private final StudentOutboxHmacKeyStore keyStore;
    private StudentOutboxAuthenticator active;
    private final StudentOutboxAuthenticityVerifier verifier;

    StudentOutboxAuthenticatorSession(String learnerId, StudentOutboxHmacKeyStore keyStore) {
        requireSessionText(learnerId, "Learner id");
        this.learnerId = learnerId;
        this.keyStore = Objects.requireNonNull(keyStore, "keyStore");
        this.verifier =
                StudentOutboxAuthenticityVerifier.ownerIssued(
                        new SessionVerificationOperation(this));
    }

    synchronized void bind(ActiveStudentOutboxAuthenticityKey activeKey) {
        StudentOutboxAuthenticator candidate =
                new StudentOutboxAuthenticator(learnerId, activeKey, keyStore);
        if (active == null) {
            active = candidate;
            return;
        }
        if (!activeKey.equals(active.activeKeyForOwnerCheck())) {
            throw new SecurityException("Student outbox authenticity key changed during runtime");
        }
    }

    StudentOutboxAuthenticityVerifier getVerifier() {
        return verifier;
    }

    private synchronized StudentOutboxVerificationReceipt verifyAuthentic(
            StudentMistakeRelayMessage message) {
        if (active == null) {
            throw new SecurityException(StudentOutboxAuthenticator.REJECTION);
        }
        return active.verifyAuthentic(message);
    }

    private static void requireSessionText(String value, String label) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(label + " must be a trimmed non-blank value");
        }
    }

    private static final class SessionVerificationOperation
            implements StudentOutboxAuthenticityVerificationOperation {
        private final StudentOutboxAuthenticatorSession session;

        private SessionVerificationOperation(StudentOutboxAuthenticatorSession session) {
            this.session = session;
        }

        @Override
        public StudentOutboxVerificationReceipt verify(StudentMistakeRelayMessage message) {
            return session.verifyAuthentic(message);
        }
    }
}

/** Owner-private key access. No public/protected API exposes a {@link SecretKey}. */
abstract class StudentOutboxHmacKeyStore {
    abstract boolean contains(String keyAlias);

    abstract void loadOrCreateBootstrap(String keyAlias);

    abstract String issueHmac(String keyAlias, String authenticatedContextFingerprint);

    abstract String issueReviewResponseBinding(
            String keyAlias, String authenticatedBindingContextFingerprint);
}

/** Android-Keystore implementation retained inside the student authority package. */
final class AndroidKeystoreStudentOutboxHmacKeyStore extends StudentOutboxHmacKeyStore {
    static final AndroidKeystoreStudentOutboxHmacKeyStore INSTANCE =
            new AndroidKeystoreStudentOutboxHmacKeyStore();

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String HMAC_ALGORITHM = KeyProperties.KEY_ALGORITHM_HMAC_SHA256;
    private static final String KEY_ALIAS_PREFIX =
            "com.tingyun.smartmistakebook.student.outbox.authenticity.hmac";
    private final Object lock = new Object();

    private AndroidKeystoreStudentOutboxHmacKeyStore() {}

    @Override
    boolean contains(String keyAlias) {
        requireKeyAlias(keyAlias);
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            return keyStore.containsAlias(keyAlias);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SecurityException("Student outbox authenticity key is unavailable", failure);
        }
    }

    @Override
    void loadOrCreateBootstrap(String keyAlias) {
        requireKeyAlias(keyAlias);
        synchronized (lock) {
            if (contains(keyAlias)) {
                return;
            }
            try {
                KeyGenerator generator =
                        KeyGenerator.getInstance(HMAC_ALGORITHM, ANDROID_KEYSTORE);
                generator.init(
                        new KeyGenParameterSpec.Builder(
                                        keyAlias, KeyProperties.PURPOSE_SIGN)
                                .setDigests(KeyProperties.DIGEST_SHA256)
                                .build());
                generator.generateKey();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new SecurityException(
                        "Student outbox authenticity key is unavailable", failure);
            }
        }
    }

    @Override
    String issueHmac(String keyAlias, String context) {
        requireKeyAlias(keyAlias);
        StudentOutboxAuthenticator.requireLowercaseSha256(
                context, "Student-outbox authenticated context fingerprint");
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            SecretKey key = (SecretKey) keyStore.getKey(keyAlias, null);
            if (key == null) {
                throw new SecurityException("Student outbox authenticity key is unavailable");
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update(StudentOutboxAuthenticityProof.MAC_DOMAIN.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            byte[] tag = mac.doFinal(context.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(tag.length * 2);
            for (byte value : tag) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SecurityException("Student outbox authenticity key is unavailable", failure);
        }
    }

    @Override
    String issueReviewResponseBinding(String keyAlias, String bindingContext) {
        requireKeyAlias(keyAlias);
        StudentOutboxAuthenticator.requireLowercaseSha256(
                bindingContext, "Student review-response binding context fingerprint");
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            SecretKey key = (SecretKey) keyStore.getKey(keyAlias, null);
            if (key == null) {
                throw new SecurityException("Student outbox authenticity key is unavailable");
            }
            return StudentReviewResponseHmac.issue(key, bindingContext);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SecurityException("Student outbox authenticity key is unavailable", failure);
        }
    }

    private static void requireKeyAlias(String keyAlias) {
        if (keyAlias == null
                || !keyAlias.startsWith(KEY_ALIAS_PREFIX + ".")
                || keyAlias.length() > 256) {
            throw new IllegalArgumentException(
                    "Invalid student-outbox authenticity key alias");
        }
    }
}
