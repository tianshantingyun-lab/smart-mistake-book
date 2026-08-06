package com.tingyun.smartmistakebook.core.mastery.database;

import android.content.Context;
import com.tingyun.smartmistakebook.core.model.ProjectionWorkloadGate;
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Package-private bridge consumed only by core:data code compiled into this exact package.
 *
 * <p>The repository architecture test forbids any other production split-package consumer.
 */
final class CoreDataLearnerMasteryOwnerBridge {
    private CoreDataLearnerMasteryOwnerBridge() {}

    static LearnerMasteryRuntimeCapabilities open(
            Context context,
            String learnerId,
            KnowledgeReferenceProofAuthority.Verifier knowledgeReferenceVerifier,
            ProjectionWorkloadGate projectionWorkloadGate) {
        return LearnerMasteryRuntimeFactory.INSTANCE.open(
                context,
                learnerId,
                knowledgeReferenceVerifier,
                LearnerMasteryOwnerKey.INSTANCE,
                OPEN_RESPONSE_ISSUER.processOwnerSeal(),
                projectionWorkloadGate);
    }

    static LearnerMasteryRelayCapability relayHandoff(
            LearnerMasteryRuntimeCapabilities runtimeOwner) {
        return Objects.requireNonNull(runtimeOwner, "runtimeOwner").relayHandoff();
    }

    static MasteryOutboxAuthenticityVerifier outboxAuthenticityVerifierHandoff(
            LearnerMasteryRuntimeCapabilities runtimeOwner) {
        return Objects.requireNonNull(runtimeOwner, "runtimeOwner")
                .outboxAuthenticityVerifierHandoff();
    }

    static LearnerMasteryCutoverControlPort openCutoverControl(
            Context context, String learnerId) {
        return LearnerMasteryCutoverControlPortFactory.INSTANCE.open(
                context,
                learnerId,
                LearnerMasteryOwnerKey.INSTANCE);
    }

    static LearnerMasteryLegacyMigrationPort openLegacyMigration(
            Context context, String learnerId) {
        return LearnerMasteryLegacyMigrationPortFactory.INSTANCE.open(
                context,
                learnerId,
                LearnerMasteryOwnerKey.INSTANCE);
    }

    static LearnerMasteryCutoverDestinationAttestationPorts
            openCutoverDestinationAttestations(Context context) {
        return LearnerMasteryCutoverDestinationAttestationPortFactory.INSTANCE.open(
                context,
                LearnerMasteryOwnerKey.INSTANCE);
    }

    static LearnerMasteryOpenResponseOwnerGrant claimOpenResponseOwnerGrant(
            LearnerMasteryRuntimeCapabilities runtimeOwner) {
        return OPEN_RESPONSE_ISSUER.claimRuntimeGrant(runtimeOwner);
    }

    static LearnerMasteryOpenResponseWeakCandidateOwner openOpenResponseWeakCandidateOwner(
            Context context,
            String learnerId,
            LearnerMasteryOpenResponseOwnerGrant ownerGrant) {
        return OPEN_RESPONSE_ISSUER.open(context, learnerId, ownerGrant);
    }

    static LearnerMasteryOpenResponseWeakCandidateAuthorization
            bindOpenResponseWeakCandidateAuthorization(
                    LearnerMasteryOpenResponseWeakCandidateOwner owner,
                    String learnerId,
                    String scopeFingerprint,
                    LearnerMasteryOpenResponseScopeLease scopeLease) {
        return OPEN_RESPONSE_ISSUER.bind(
                owner, learnerId, scopeFingerprint, scopeLease);
    }

    static void requireOpenResponseOwnerSeal(Object ownerSeal) {
        OPEN_RESPONSE_ISSUER.requireOwnerSeal(ownerSeal);
    }

    static boolean isIssuedOpenResponseAuthorization(
            LearnerMasteryOpenResponseWeakCandidateAuthorization authorization,
            RoomOpenResponseWeakCandidateOwner owner,
            Object ownerSeal) {
        return OPEN_RESPONSE_ISSUER.isIssuedAuthorization(
                authorization, owner, ownerSeal);
    }

    static void revokeOpenResponseAuthorization(
            LearnerMasteryOpenResponseWeakCandidateAuthorization authorization,
            RoomOpenResponseWeakCandidateOwner owner,
            Object ownerSeal) {
        OPEN_RESPONSE_ISSUER.revokeAuthorization(authorization, owner, ownerSeal);
    }

    static void closeOpenResponseOwner(
            RoomOpenResponseWeakCandidateOwner owner, Object ownerSeal) {
        OPEN_RESPONSE_ISSUER.closeOwner(owner, ownerSeal);
    }

    static void registerOpenResponseRuntime(
            LearnerMasteryRuntimeCapabilities runtimeOwner,
            String learnerId,
            Object ownerSeal) {
        OPEN_RESPONSE_ISSUER.registerRuntime(runtimeOwner, learnerId, ownerSeal);
    }

    static void revokeOpenResponseRuntime(
            LearnerMasteryRuntimeCapabilities runtimeOwner,
            Object ownerSeal) {
        OPEN_RESPONSE_ISSUER.revokeRuntime(runtimeOwner, ownerSeal);
    }

    /**
     * Binds only an already-verified stage-nine receipt. The destination engine still recomputes
     * the physical migration ledger before it issues a destination binding or proof.
     */
    static LearnerMasteryFactsImportReceiptReference bindVerifiedFactsImportReceipt(
            String learnerId,
            long cutoverGeneration,
            String legacyPrefixFingerprint,
            long migratedRecordCount,
            String sourceGeneration,
            String sourceCheckpoint,
            String destinationFingerprint,
            String receiptFingerprint) {
        return LearnerMasteryFactsImportReceiptReferenceFactory.INSTANCE.bind(
                learnerId,
                cutoverGeneration,
                legacyPrefixFingerprint,
                migratedRecordCount,
                sourceGeneration,
                sourceCheckpoint,
                destinationFingerprint,
                receiptFingerprint,
                LearnerMasteryOwnerKey.INSTANCE);
    }

    private static final OpenResponsePrivateIssuer OPEN_RESPONSE_ISSUER =
            new OpenResponsePrivateIssuer();

    /** Java-private issuer; neither its seal nor its registration maps escape this class. */
    private static final class OpenResponsePrivateIssuer {
        private final Object processOwnerSeal = new Object();
        private final AtomicLong nextRuntimeGeneration = new AtomicLong(1L);
        private final Map<LearnerMasteryRuntimeCapabilities, RuntimeRecord> runtimes =
                Collections.synchronizedMap(new IdentityHashMap<>());
        private final Map<LearnerMasteryOpenResponseOwnerGrant, RuntimeRecord> grants =
                Collections.synchronizedMap(new IdentityHashMap<>());
        private final Map<RoomOpenResponseWeakCandidateOwner, RuntimeRecord> owners =
                Collections.synchronizedMap(new IdentityHashMap<>());
        private final Map<
                        LearnerMasteryOpenResponseWeakCandidateAuthorization,
                        RoomOpenResponseWeakCandidateOwner>
                authorizations =
                        Collections.synchronizedMap(new IdentityHashMap<>());

        Object processOwnerSeal() {
            return processOwnerSeal;
        }

        void registerRuntime(
                LearnerMasteryRuntimeCapabilities runtimeOwner,
                String learnerId,
                Object ownerSeal) {
            requireOwnerSeal(ownerSeal);
            Objects.requireNonNull(runtimeOwner, "runtimeOwner");
            Objects.requireNonNull(learnerId, "learnerId");
            long generation = nextRuntimeGeneration.getAndIncrement();
            if (generation <= 0L) {
                throw new IllegalStateException(
                        "Open-response runtime generation exhausted");
            }
            synchronized (runtimes) {
                if (runtimes.put(
                                runtimeOwner,
                                new RuntimeRecord(learnerId, generation))
                        != null) {
                    throw new IllegalStateException(
                            "Learner-mastery runtime was registered twice");
                }
            }
        }

        LearnerMasteryOpenResponseOwnerGrant claimRuntimeGrant(
                LearnerMasteryRuntimeCapabilities runtimeOwner) {
            Objects.requireNonNull(runtimeOwner, "runtimeOwner");
            RuntimeRecord record;
            synchronized (runtimes) {
                record = runtimes.get(runtimeOwner);
                if (record == null || !record.active || record.grantClaimed) {
                    throw new SecurityException(
                            "Open-response owner grant is unavailable for this runtime generation");
                }
                record.grantClaimed = true;
            }
            LearnerMasteryOpenResponseOwnerGrant grant =
                    new LearnerMasteryOpenResponseOwnerGrant(processOwnerSeal);
            synchronized (grants) {
                if (grants.put(grant, record) != null) {
                    throw new IllegalStateException(
                            "Open-response owner grant was issued twice");
                }
            }
            return grant;
        }

        LearnerMasteryOpenResponseWeakCandidateOwner open(
                Context context,
                String learnerId,
                LearnerMasteryOpenResponseOwnerGrant grant) {
            Objects.requireNonNull(grant, "ownerGrant");
            RuntimeRecord record;
            synchronized (grants) {
                record = grants.remove(grant);
            }
            if (record == null
                    || !record.active
                    || !record.learnerId.equals(learnerId)
                    || grant.issuerSealForDatabase() != processOwnerSeal) {
                throw new SecurityException(
                        "Open-response owner grant is stale, replayed, or outside learner scope");
            }
            RoomOpenResponseWeakCandidateOwner owner =
                    OpenResponseWeakCandidateOwnerFactory.INSTANCE.open(
                            context, learnerId, processOwnerSeal);
            registerOwner(owner, record);
            return owner;
        }

        LearnerMasteryOpenResponseWeakCandidateAuthorization bind(
                LearnerMasteryOpenResponseWeakCandidateOwner ownerCapability,
                String learnerId,
                String scopeFingerprint,
                LearnerMasteryOpenResponseScopeLease scopeLease) {
            Objects.requireNonNull(ownerCapability, "owner");
            if (!(ownerCapability instanceof RoomOpenResponseWeakCandidateOwner)) {
                throw new SecurityException(
                        "Open-response authorization requires the database-issued owner");
            }
            RoomOpenResponseWeakCandidateOwner owner =
                    (RoomOpenResponseWeakCandidateOwner) ownerCapability;
            synchronized (owners) {
                if (!owners.containsKey(owner)
                        || !owner.hasOpenResponseOwnerSeal(processOwnerSeal)
                        || owner.isClosedForIssuer(processOwnerSeal)
                        || !owner.getLearnerId().equals(learnerId)) {
                    throw new SecurityException(
                            "Open-response authorization owner is not active or learner-bound");
                }
            }
            long expectedEpoch =
                    Objects.requireNonNull(scopeLease, "scopeLease")
                            .requireCurrentEpoch();
            if (expectedEpoch < 0L) {
                throw new IllegalStateException(
                        "Open-response scope epoch must not be negative");
            }
            LearnerMasteryOpenResponseWeakCandidateAuthorization authorization =
                    new LearnerMasteryOpenResponseWeakCandidateAuthorization(
                            processOwnerSeal,
                            owner,
                            learnerId,
                            scopeFingerprint,
                            scopeLease,
                            expectedEpoch);
            synchronized (authorizations) {
                if (authorizations.put(authorization, owner) != null) {
                    throw new IllegalStateException(
                            "Open-response authorization was issued twice");
                }
            }
            return authorization;
        }

        void requireOwnerSeal(Object ownerSeal) {
            if (ownerSeal != processOwnerSeal) {
                throw new SecurityException(
                        "Open-response owner seal was not issued by the database");
            }
        }

        boolean isIssuedAuthorization(
                LearnerMasteryOpenResponseWeakCandidateAuthorization authorization,
                RoomOpenResponseWeakCandidateOwner owner,
                Object ownerSeal) {
            if (ownerSeal != processOwnerSeal) {
                return false;
            }
            synchronized (authorizations) {
                return authorizations.get(authorization) == owner;
            }
        }

        void revokeAuthorization(
                LearnerMasteryOpenResponseWeakCandidateAuthorization authorization,
                RoomOpenResponseWeakCandidateOwner owner,
                Object ownerSeal) {
            if (ownerSeal != processOwnerSeal) {
                return;
            }
            synchronized (authorizations) {
                if (authorizations.get(authorization) == owner) {
                    authorizations.remove(authorization);
                }
            }
        }

        void closeOwner(RoomOpenResponseWeakCandidateOwner owner, Object ownerSeal) {
            requireOwnerSeal(ownerSeal);
            synchronized (owners) {
                owners.remove(owner);
            }
            synchronized (authorizations) {
                authorizations.entrySet().removeIf(entry -> entry.getValue() == owner);
            }
        }

        void revokeRuntime(
                LearnerMasteryRuntimeCapabilities runtimeOwner,
                Object ownerSeal) {
            requireOwnerSeal(ownerSeal);
            RuntimeRecord record;
            synchronized (runtimes) {
                record = runtimes.remove(runtimeOwner);
                if (record == null) {
                    return;
                }
                record.active = false;
            }
            synchronized (grants) {
                grants.entrySet().removeIf(entry -> entry.getValue() == record);
            }
            List<RoomOpenResponseWeakCandidateOwner> revokedOwners =
                    new ArrayList<>();
            synchronized (owners) {
                owners.entrySet().removeIf(
                        entry -> {
                            if (entry.getValue() == record) {
                                revokedOwners.add(entry.getKey());
                                return true;
                            }
                            return false;
                        });
            }
            for (RoomOpenResponseWeakCandidateOwner owner : revokedOwners) {
                owner.revokeFromRuntime(processOwnerSeal);
            }
        }

        private void registerOwner(
                RoomOpenResponseWeakCandidateOwner owner,
                RuntimeRecord runtime) {
            synchronized (owners) {
                if (owners.put(owner, runtime) != null) {
                    throw new IllegalStateException(
                            "Open-response database owner was issued twice");
                }
            }
        }

        private static final class RuntimeRecord {
            final String learnerId;
            final long generation;
            boolean active = true;
            boolean grantClaimed = false;

            RuntimeRecord(String learnerId, long generation) {
                this.learnerId = learnerId;
                this.generation = generation;
            }
        }
    }
}
