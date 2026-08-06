package com.tingyun.smartmistakebook.core.mastery.database;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import com.tingyun.smartmistakebook.core.model.CanonicalSha256;
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope;
import com.tingyun.smartmistakebook.core.model.storage.LearnerMasteryRelayMessage;
import com.tingyun.smartmistakebook.core.model.storage.LearningAttemptRecordedV1;
import com.tingyun.smartmistakebook.core.model.storage.MasteryOutboxAuthenticityProof;
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;

abstract class MasteryOutboxAuthenticityIssuer {
    abstract LearnerMasteryRelayMessage attest(CrossStoreEventEnvelope envelope);
}

final class MasteryOutboxInvalidProofException extends SecurityException {
    MasteryOutboxInvalidProofException(String message) { super(message); }
    MasteryOutboxInvalidProofException(String message, Throwable cause) { super(message, cause); }
}

/** Owner-private, learner-bound signer and verifier for mastery-to-student delivery. */
final class MasteryOutboxAuthenticator extends MasteryOutboxAuthenticityIssuer {
    static final int KEY_VERSION = 1;
    static final String REJECTION = "Learner-mastery outbox authenticity verification failed";
    private static final Pattern LOWERCASE_SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private final String learnerId;
    private final ActiveMasteryOutboxAuthenticityKey activeKey;
    private final MasteryOutboxHmacKeyStore keyStore;

    MasteryOutboxAuthenticator(
            String learnerId,
            ActiveMasteryOutboxAuthenticityKey activeKey,
            MasteryOutboxHmacKeyStore keyStore) {
        requireText(learnerId, "Learner id");
        this.learnerId = learnerId;
        this.activeKey = Objects.requireNonNull(activeKey, "activeKey");
        this.keyStore = Objects.requireNonNull(keyStore, "keyStore");
        if (!MasteryOutboxAuthenticityProof.ALGORITHM_VERSION.equals(
                activeKey.getAlgorithmVersion())) {
            throw new IllegalArgumentException("Unsupported mastery-outbox algorithm");
        }
    }

    ActiveMasteryOutboxAuthenticityKey activeKeyForOwnerCheck() { return activeKey; }

    @Override
    LearnerMasteryRelayMessage attest(CrossStoreEventEnvelope envelope) {
        requireActiveGeneration(envelope);
        String context = authenticatedContextFingerprint(
                envelope, learnerId, activeKey.getKeyId(), activeKey.getAlgorithmVersion());
        MasteryOutboxAuthenticityProof proof = new MasteryOutboxAuthenticityProof(
                MasteryOutboxAuthenticityProof.PROTOCOL_VERSION,
                activeKey.getAlgorithmVersion(),
                activeKey.getKeyId(),
                learnerId,
                envelope.getCanonicalFingerprint(),
                keyStore.issueHmac(activeKey.getKeyAlias(), context));
        return LearnerMasteryRelayMessage.fromUnverifiedEnvelopeAndProof(envelope, proof);
    }

    MasteryOutboxVerificationReceipt verifyAuthentic(LearnerMasteryRelayMessage message) {
        CrossStoreEventEnvelope envelope = message.getEnvelope();
        MasteryOutboxAuthenticityProof proof = message.getAuthenticityProof();
        String context;
        try {
            requireActiveGeneration(envelope);
            if (proof.getProtocolVersion() != MasteryOutboxAuthenticityProof.PROTOCOL_VERSION
                    || !proof.getAlgorithmVersion().equals(activeKey.getAlgorithmVersion())
                    || !proof.getIssuerKeyId().equals(activeKey.getKeyId())
                    || !proof.getLearnerId().equals(learnerId)
                    || !proof.getEnvelopeCanonicalFingerprint().equals(
                            envelope.getCanonicalFingerprint())) {
                throw new MasteryOutboxInvalidProofException(
                        "Learner-mastery outbox proof scope mismatch");
            }
            context = authenticatedContextFingerprint(
                    envelope, learnerId, activeKey.getKeyId(), activeKey.getAlgorithmVersion());
        } catch (MasteryOutboxInvalidProofException failure) {
            throw failure;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new MasteryOutboxInvalidProofException(REJECTION, failure);
        }
        String expected = keyStore.issueHmac(activeKey.getKeyAlias(), context);
        if (!constantTimeHexEquals(expected, proof.getTagHex())) {
            throw new MasteryOutboxInvalidProofException(
                    "Learner-mastery outbox proof tag mismatch");
        }
        return MasteryOutboxVerificationReceipt.ownerIssued(
                message,
                activeKey.getSourceStoreGeneration(),
                activeKey.getRelayEpoch(),
                activeKey.getKeyId(),
                activeKey.getAlgorithmVersion());
    }

    private void requireActiveGeneration(CrossStoreEventEnvelope envelope) {
        requireEnvelopeScope(envelope, learnerId);
        if (!envelope.getSourceStoreGeneration().equals(activeKey.getSourceStoreGeneration())) {
            throw new MasteryOutboxInvalidProofException(REJECTION);
        }
    }

    static String authenticatedContextFingerprint(
            CrossStoreEventEnvelope envelope,
            String learnerId,
            String issuerKeyId,
            String algorithmVersion) {
        requireEnvelopeScope(envelope, learnerId);
        requireText(issuerKeyId, "Learner-mastery outbox key id");
        if (!MasteryOutboxAuthenticityProof.ALGORITHM_VERSION.equals(algorithmVersion)) {
            throw new IllegalArgumentException("Unsupported mastery-outbox algorithm");
        }
        return new CanonicalSha256(
                        MasteryOutboxAuthenticityProof.AUTHENTICATED_CONTEXT_DOMAIN)
                .field("proofProtocolVersion", MasteryOutboxAuthenticityProof.PROTOCOL_VERSION)
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

    private static void requireEnvelopeScope(CrossStoreEventEnvelope envelope, String learnerId) {
        if (envelope.getSourceStore() != StudyStoreKind.LEARNER_MASTERY
                || envelope.getDestinationStore() != StudyStoreKind.STUDENT_MISTAKES
                || !(envelope.getPayload() instanceof LearningAttemptRecordedV1 payload)
                || !payload.getEvidence().getLearnerId().equals(learnerId)
                || !payload.getProblemRevision().getProblem().getLearnerId().equals(learnerId)
                || !envelope.getAggregateId().equals(payload.getEvidence().getEventId())
                || envelope.getAggregateVersion() != payload.getEvidence().getEventSequence()
                || envelope.getOccurredAtEpochMillis() != payload.getRecordedAtEpochMillis()) {
            throw new IllegalArgumentException(
                    "Learner-mastery outbox payload is outside the bound route or scope");
        }
    }

    static void requireLowercaseSha256(String value, String label) {
        if (!LOWERCASE_SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must be a lowercase SHA-256 value");
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

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank() || !value.equals(value.trim())
                || value.length() > 256 || containsIsoControl(value)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
    }

    private static boolean containsIsoControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }
}

/** Stable verify-only holder published before the first asynchronous outbox read. */
final class MasteryOutboxAuthenticatorSession {
    private final String learnerId;
    private final MasteryOutboxHmacKeyStore keyStore;
    private MasteryOutboxAuthenticator active;
    private final MasteryOutboxAuthenticityVerifier verifier;

    MasteryOutboxAuthenticatorSession(String learnerId, MasteryOutboxHmacKeyStore keyStore) {
        this.learnerId = Objects.requireNonNull(learnerId, "learnerId");
        this.keyStore = Objects.requireNonNull(keyStore, "keyStore");
        this.verifier = MasteryOutboxAuthenticityVerifier.ownerIssued(
                new SessionVerificationOperation(this));
    }

    synchronized MasteryOutboxAuthenticator bind(ActiveMasteryOutboxAuthenticityKey activeKey) {
        MasteryOutboxAuthenticator candidate =
                new MasteryOutboxAuthenticator(learnerId, activeKey, keyStore);
        if (active == null) {
            active = candidate;
        } else if (!activeKey.equals(active.activeKeyForOwnerCheck())) {
            throw new SecurityException(
                    "Learner-mastery outbox key changed during the runtime");
        }
        return active;
    }

    MasteryOutboxAuthenticityVerifier getVerifier() { return verifier; }

    private synchronized MasteryOutboxVerificationReceipt verify(
            LearnerMasteryRelayMessage message) {
        if (active == null) throw new SecurityException(MasteryOutboxAuthenticator.REJECTION);
        return active.verifyAuthentic(message);
    }

    private static final class SessionVerificationOperation
            implements MasteryOutboxAuthenticityVerificationOperation {
        private final MasteryOutboxAuthenticatorSession session;
        private SessionVerificationOperation(MasteryOutboxAuthenticatorSession session) {
            this.session = session;
        }
        @Override public MasteryOutboxVerificationReceipt verify(
                LearnerMasteryRelayMessage message) {
            return session.verify(message);
        }
    }
}

abstract class MasteryOutboxHmacKeyStore {
    abstract boolean contains(String keyAlias);
    abstract void loadOrCreateBootstrap(String keyAlias);
    abstract String issueHmac(String keyAlias, String authenticatedContextFingerprint);
}

final class AndroidKeystoreMasteryOutboxHmacKeyStore extends MasteryOutboxHmacKeyStore {
    static final AndroidKeystoreMasteryOutboxHmacKeyStore INSTANCE =
            new AndroidKeystoreMasteryOutboxHmacKeyStore();
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS_PREFIX =
            "com.tingyun.smartmistakebook.mastery.outbox.authenticity.hmac";
    private final Object lock = new Object();

    private AndroidKeystoreMasteryOutboxHmacKeyStore() {}

    @Override boolean contains(String keyAlias) {
        requireKeyAlias(keyAlias);
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            return keyStore.containsAlias(keyAlias);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SecurityException("Learner-mastery outbox key is unavailable", failure);
        }
    }

    @Override void loadOrCreateBootstrap(String keyAlias) {
        requireKeyAlias(keyAlias);
        synchronized (lock) {
            if (contains(keyAlias)) return;
            try {
                KeyGenerator generator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE);
                generator.init(new KeyGenParameterSpec.Builder(
                                keyAlias, KeyProperties.PURPOSE_SIGN)
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build());
                generator.generateKey();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new SecurityException(
                        "Learner-mastery outbox key is unavailable", failure);
            }
        }
    }

    @Override String issueHmac(String keyAlias, String context) {
        requireKeyAlias(keyAlias);
        MasteryOutboxAuthenticator.requireLowercaseSha256(
                context, "Learner-mastery authenticated context fingerprint");
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            SecretKey key = (SecretKey) keyStore.getKey(keyAlias, null);
            if (key == null) throw new SecurityException(
                    "Learner-mastery outbox key is unavailable");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            mac.update(MasteryOutboxAuthenticityProof.MAC_DOMAIN.getBytes(
                    StandardCharsets.UTF_8));
            mac.update((byte) 0);
            byte[] tag = mac.doFinal(context.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(tag.length * 2);
            for (byte value : tag) result.append(String.format("%02x", value & 0xff));
            return result.toString();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SecurityException("Learner-mastery outbox key is unavailable", failure);
        }
    }

    private static void requireKeyAlias(String keyAlias) {
        if (keyAlias == null || !keyAlias.startsWith(KEY_ALIAS_PREFIX + ".")
                || keyAlias.length() > 256) {
            throw new IllegalArgumentException("Invalid learner-mastery outbox key alias");
        }
    }
}
