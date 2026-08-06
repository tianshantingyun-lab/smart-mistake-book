package com.tingyun.smartmistakebook.core.data.authority;

import android.content.Context;
import com.tingyun.smartmistakebook.core.data.capture.CaptureDraftSessionPort;
import com.tingyun.smartmistakebook.core.data.capture.ProductionCaptureSessionPort;
import com.tingyun.smartmistakebook.core.data.session.BatchImportSessionPort;
import com.tingyun.smartmistakebook.core.data.session.SessionScope;
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository;
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import kotlinx.coroutines.Job;

/** Identity registry for the authenticated construction-claim -> final owner-claim hand-off. */
final class ProductionBatchImportConstructionRegistry {
    private static final Object MONITOR = new Object();
    private static final Map<CurrentGenerationBatchImportConstructionClaim, State> CLAIMS =
            new IdentityHashMap<>();
    private static final Map<CurrentGenerationBatchImportConstructionResources, State> RESOURCES =
            new IdentityHashMap<>();
    private static final Map<CurrentGenerationBatchImportConstructionResources, TransferState>
            TRANSFERS = new IdentityHashMap<>();

    private ProductionBatchImportConstructionRegistry() {}

    static CurrentGenerationBatchImportConstructionClaim issue(
            Context canonicalContext,
            SessionScope sessionScope,
            BatchImportSessionPort batchSessions,
            ProductionCaptureSessionPort captureDrafts,
            ModelTaskRepository modelTaskQueue,
            ProductionBatchImportAuthorityBindings bindings) {
        final State state =
                new State(
                        canonicalContext,
                        sessionScope,
                        batchSessions,
                        captureDrafts,
                        modelTaskQueue,
                        bindings);
        final CurrentGenerationBatchImportConstructionClaim claim =
                CurrentGenerationBatchImportConstructionClaim.newRegistryShell();
        synchronized (MONITOR) {
            if (CLAIMS.put(claim, state) != null) {
                throw new IllegalStateException(
                        "Batch-import construction claim identity was reused");
            }
        }
        return claim;
    }

    static CurrentGenerationBatchImportConstructionResources transferClaim(
            CurrentGenerationBatchImportConstructionClaim claim) {
        Objects.requireNonNull(claim, "constructionClaim");
        final State state;
        final CurrentGenerationBatchImportConstructionResources resources;
        synchronized (MONITOR) {
            state = CLAIMS.remove(claim);
            if (state == null) {
                throw new IllegalStateException(
                        "Batch-import construction claim is revoked, forged, or already claimed");
            }
            resources = CurrentGenerationBatchImportConstructionResources.newRegistryShell();
            if (RESOURCES.put(resources, state) != null) {
                throw new IllegalStateException(
                        "Batch-import construction resource identity was reused");
            }
        }
        return resources;
    }

    static State active(CurrentGenerationBatchImportConstructionResources resources) {
        Objects.requireNonNull(resources, "constructionResources");
        synchronized (MONITOR) {
            final State state = RESOURCES.get(resources);
            if (state == null) {
                throw new IllegalStateException(
                        "Batch-import construction resources are revoked, forged, or consumed");
            }
            return state;
        }
    }

    static void claimRawConstruction(
            CurrentGenerationBatchImportConstructionResources resources) {
        Objects.requireNonNull(resources, "constructionResources");
        synchronized (MONITOR) {
            final State state = RESOURCES.get(resources);
            if (state == null) {
                throw new IllegalStateException(
                        "Batch-import construction resources are revoked, forged, or consumed");
            }
            state.claimRawConstruction();
        }
    }

    static CurrentGenerationBatchImportClaim registerBuilt(
            CurrentGenerationBatchImportConstructionResources resources,
            BatchImportRepository repository,
            Job processingJob,
            ProductionBatchImportAuthorityBindings builtBindings) {
        Objects.requireNonNull(resources, "constructionResources");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(processingJob, "processingJob");
        Objects.requireNonNull(builtBindings, "builtBindings");

        final State state;
        final TransferState transferState;
        synchronized (MONITOR) {
            state = RESOURCES.remove(resources);
            if (state == null) {
                cancelAfterFailedRegistration(processingJob);
                throw new IllegalStateException(
                        "Batch-import construction resources are revoked, forged, or consumed");
            }
            try {
                state.requireRawConstructionClaimed();
            } catch (RuntimeException | Error failure) {
                cancelAfterFailedRegistration(processingJob);
                throw failure;
            }
            transferState = new TransferState();
            TRANSFERS.put(resources, transferState);
        }

        CurrentGenerationBatchImportClaim finalClaim = null;
        try {
            finalClaim =
                    ProductionBatchImportOwnerRegistry.registerBuilt(
                            repository,
                            processingJob,
                            state.bindings,
                            builtBindings);
            synchronized (MONITOR) {
                if (TRANSFERS.remove(resources) != transferState || transferState.revoked) {
                    throw new IllegalStateException(
                            "Batch-import construction resources were revoked during registration");
                }
            }
            return finalClaim;
        } catch (RuntimeException | Error failure) {
            synchronized (MONITOR) {
                TRANSFERS.remove(resources);
            }
            if (finalClaim != null) {
                closeAfterFailure(finalClaim, failure);
            } else {
                cancelAfterFailedRegistration(processingJob);
            }
            throw failure;
        }
    }

    static void closeClaim(CurrentGenerationBatchImportConstructionClaim claim) {
        synchronized (MONITOR) {
            CLAIMS.remove(claim);
        }
    }

    static void closeResources(CurrentGenerationBatchImportConstructionResources resources) {
        synchronized (MONITOR) {
            RESOURCES.remove(resources);
            final TransferState transfer = TRANSFERS.get(resources);
            if (transfer != null) {
                transfer.revoked = true;
            }
        }
    }

    private static void cancelAfterFailedRegistration(Job processingJob) {
        processingJob.cancel(
                new CancellationException("Batch-import construction registration failed"));
    }

    private static void closeAfterFailure(
            CurrentGenerationBatchImportClaim claim,
            Throwable ownerFailure) {
        try {
            claim.close();
        } catch (RuntimeException | Error closeFailure) {
            ownerFailure.addSuppressed(closeFailure);
        }
    }

    static final class State {
        private final Context canonicalContext;
        private final SessionScope sessionScope;
        private final BatchImportSessionPort batchSessions;
        private final CaptureDraftSessionPort captureDrafts;
        private final ModelTaskRepository modelTaskQueue;
        private final ProductionBatchImportAuthorityBindings bindings;
        private boolean rawConstructionClaimed;

        private State(
                Context canonicalContext,
                SessionScope sessionScope,
                BatchImportSessionPort batchSessions,
                ProductionCaptureSessionPort captureDrafts,
                ModelTaskRepository modelTaskQueue,
                ProductionBatchImportAuthorityBindings bindings) {
            this.canonicalContext =
                    Objects.requireNonNull(canonicalContext, "canonicalContext");
            this.sessionScope = Objects.requireNonNull(sessionScope, "sessionScope");
            this.batchSessions = Objects.requireNonNull(batchSessions, "batchSessions");
            this.captureDrafts = Objects.requireNonNull(captureDrafts, "captureDrafts");
            this.modelTaskQueue = Objects.requireNonNull(modelTaskQueue, "modelTaskQueue");
            this.bindings = Objects.requireNonNull(bindings, "bindings");
            if (bindings.context() != canonicalContext) {
                throw new IllegalArgumentException(
                        "Batch-import context identity is not the canonical context");
            }
            if (!captureDrafts.getLearnerId().equals(sessionScope.getLearnerId())) {
                throw new IllegalArgumentException(
                        "Batch-import capture and session scopes belong to different learners");
            }
        }

        Context canonicalContext() {
            return canonicalContext;
        }

        SessionScope sessionScope() {
            return sessionScope;
        }

        BatchImportSessionPort batchSessions() {
            return batchSessions;
        }

        CaptureDraftSessionPort captureDrafts() {
            return captureDrafts;
        }

        ModelTaskRepository modelTaskQueue() {
            return modelTaskQueue;
        }

        ProductionBatchImportAuthorityBindings bindings() {
            return bindings;
        }

        private void claimRawConstruction() {
            if (rawConstructionClaimed) {
                throw new IllegalStateException(
                        "Batch-import raw construction was already claimed");
            }
            rawConstructionClaimed = true;
        }

        private void requireRawConstructionClaimed() {
            if (!rawConstructionClaimed) {
                throw new IllegalStateException(
                        "Batch-import raw construction was not claimed");
            }
        }
    }

    private static final class TransferState {
        private boolean revoked;
    }
}
