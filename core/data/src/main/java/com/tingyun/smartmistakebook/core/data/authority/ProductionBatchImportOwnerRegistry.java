package com.tingyun.smartmistakebook.core.data.authority;

import com.tingyun.smartmistakebook.core.domain.BatchImportJob;
import com.tingyun.smartmistakebook.core.domain.BatchImportOrganizationApproval;
import com.tingyun.smartmistakebook.core.domain.BatchImportOrganizationOffer;
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository;
import com.tingyun.smartmistakebook.core.domain.CreateBatchImportRequest;
import com.tingyun.smartmistakebook.core.domain.CreatePdfImportRequest;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.CompletableJob;
import kotlinx.coroutines.DisposableHandle;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.JobKt;
import kotlinx.coroutines.flow.Flow;

/** Process-local identity owner for current-generation batch-import claims and owners. */
final class ProductionBatchImportOwnerRegistry {
    private static final Object MONITOR = new Object();
    private static final Map<CurrentGenerationBatchImportClaim, RegisteredResources> CLAIMS =
            new IdentityHashMap<>();
    private static final Map<CurrentGenerationBatchImportClaim, TransferState> TRANSFERS =
            new IdentityHashMap<>();
    private static final Map<ProductionBatchImportOwner, ActiveOwner> OWNERS =
            new IdentityHashMap<>();
    private static final Map<ProductionBatchImportRepositoryLease, ProductionBatchImportOwner>
            REPOSITORY_LEASES = new IdentityHashMap<>();

    private ProductionBatchImportOwnerRegistry() {}

    static CurrentGenerationBatchImportClaim registerBuilt(
            BatchImportRepository repository,
            Job processingJob,
            ProductionBatchImportAuthorityBindings expectedBindings,
            ProductionBatchImportAuthorityBindings builtBindings) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(processingJob, "processingJob");
        Objects.requireNonNull(expectedBindings, "expectedBindings");
        Objects.requireNonNull(builtBindings, "builtBindings");
        if (!expectedBindings.hasSameIdentities(builtBindings)) {
            processingJob.cancel(
                    new CancellationException(
                            "Batch-import construction crossed authority bindings"));
            throw new IllegalStateException(
                    "Batch-import resources crossed generation or learner bindings");
        }

        final ActiveOwner active = new ActiveOwner(repository, processingJob, builtBindings);
        final RegisteredResources resources =
                new RegisteredResources(expectedBindings, () -> active, active);
        final CurrentGenerationBatchImportClaim claim =
                CurrentGenerationBatchImportClaim.newRegistryShell();
        try {
            synchronized (MONITOR) {
                if (CLAIMS.put(claim, resources) != null) {
                    throw new IllegalStateException("Batch-import claim identity was reused");
                }
            }
            return claim;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(active, failure);
            throw failure;
        }
    }

    static ProductionBatchImportOwner transfer(CurrentGenerationBatchImportClaim claim) {
        Objects.requireNonNull(claim, "currentGenerationClaim");
        final RegisteredResources resources;
        final TransferState transferState;
        synchronized (MONITOR) {
            resources = CLAIMS.remove(claim);
            if (resources == null) {
                throw new IllegalStateException(
                        "Batch-import claim is revoked, forged, or already claimed");
            }
            transferState = new TransferState();
            TRANSFERS.put(claim, transferState);
        }

        ActiveOwner active = null;
        ProductionBatchImportOwner owner = null;
        try {
            active = resources.builder.build();
            owner = ProductionBatchImportOwner.newRegistryShell();
            synchronized (MONITOR) {
                if (TRANSFERS.remove(claim) != transferState || transferState.revoked) {
                    throw new IllegalStateException(
                            "Batch-import claim was revoked during owner construction");
                }
                if (!resources.matches(active)) {
                    throw new IllegalStateException(
                            "Batch-import resources crossed generation or learner bindings");
                }
                if (OWNERS.put(owner, active) != null) {
                    throw new IllegalStateException("Batch-import owner identity was reused");
                }
                resources.releaseUnclaimedOwnership();
            }
            return owner;
        } catch (RuntimeException | Error failure) {
            synchronized (MONITOR) {
                if (owner != null) {
                    OWNERS.remove(owner);
                }
                TRANSFERS.remove(claim);
            }
            if (active != null) {
                closeAfterFailure(active, failure);
            }
            resources.closeUnclaimedAfterFailure(failure);
            throw failure;
        }
    }

    static BatchImportRepository claimRepository(ProductionBatchImportOwner owner) {
        final ProductionBatchImportRepositoryLease repositoryLease;
        synchronized (MONITOR) {
            final ActiveOwner active = OWNERS.get(owner);
            if (active == null || !active.closeSignal.isActive()) {
                throw new IllegalStateException(
                        "Batch-import owner is revoked, forged, or closed");
            }
            if (!active.repositoryClaimed.compareAndSet(false, true)) {
                throw new IllegalStateException("Batch-import repository was already claimed");
            }
            repositoryLease = ProductionBatchImportRepositoryLease.newRegistryShell();
            active.repositoryLease = repositoryLease;
            if (REPOSITORY_LEASES.put(repositoryLease, owner) != null) {
                throw new IllegalStateException("Batch-import repository lease identity was reused");
            }
        }
        return new OwnerBoundBatchImportRepository(repositoryLease);
    }

    static ActiveOwnerAccess activeAccess(ProductionBatchImportRepositoryLease repositoryLease) {
        synchronized (MONITOR) {
            final ProductionBatchImportOwner owner = REPOSITORY_LEASES.get(repositoryLease);
            final ActiveOwner active = OWNERS.get(owner);
            if (active == null ||
                    active.repositoryLease != repositoryLease ||
                    !active.closeSignal.isActive()) {
                throw new IllegalStateException(
                        "Batch-import repository lease is revoked, forged, or closed");
            }
            return new ActiveOwnerAccess(
                    new RevocableRepository(repositoryLease),
                    active.closeSignal);
        }
    }

    static boolean isActive(ProductionBatchImportRepositoryLease repositoryLease) {
        synchronized (MONITOR) {
            final ProductionBatchImportOwner owner = REPOSITORY_LEASES.get(repositoryLease);
            final ActiveOwner active = OWNERS.get(owner);
            return active != null &&
                    active.repositoryLease == repositoryLease &&
                    active.closeSignal.isActive();
        }
    }

    static void closeClaim(CurrentGenerationBatchImportClaim claim) {
        final RegisteredResources resources;
        synchronized (MONITOR) {
            resources = CLAIMS.remove(claim);
            final TransferState transferState = TRANSFERS.get(claim);
            if (transferState != null) {
                transferState.revoked = true;
            }
        }
        if (resources != null) {
            resources.closeUnclaimed();
        }
    }

    static void closeOwner(ProductionBatchImportOwner owner) {
        final ActiveOwner active;
        synchronized (MONITOR) {
            active = OWNERS.remove(owner);
            if (active != null && active.repositoryLease != null) {
                REPOSITORY_LEASES.remove(active.repositoryLease);
            }
        }
        if (active != null) {
            active.close();
        }
    }

    private static void closeAfterFailure(ActiveOwner active, Throwable ownerFailure) {
        try {
            active.close();
        } catch (RuntimeException | Error closeFailure) {
            ownerFailure.addSuppressed(closeFailure);
        }
    }

    private interface CompositeBuilder {
        ActiveOwner build();
    }

    /**
     * Identity bindings are retained for the lifetime of the unclaimed capability. A future
     * issuer must supply the canonical context, learner, generation, shared session owner, and
     * shared model owner from one authenticated current-generation resource record.
     */
    private static final class RegisteredResources {
        private final ProductionBatchImportAuthorityBindings bindings;
        private final CompositeBuilder builder;
        private AutoCloseable unclaimedOwner;

        private RegisteredResources(
                ProductionBatchImportAuthorityBindings bindings,
                CompositeBuilder builder,
                AutoCloseable unclaimedOwner) {
            this.bindings = Objects.requireNonNull(bindings, "bindings");
            this.builder = Objects.requireNonNull(builder, "builder");
            this.unclaimedOwner = Objects.requireNonNull(unclaimedOwner, "unclaimedOwner");
        }

        private boolean matches(ActiveOwner active) {
            return bindings.hasSameIdentities(active.bindings);
        }

        private synchronized void releaseUnclaimedOwnership() {
            unclaimedOwner = null;
        }

        private synchronized void closeUnclaimed() {
            final AutoCloseable owned = unclaimedOwner;
            unclaimedOwner = null;
            if (owned == null) {
                return;
            }
            try {
                owned.close();
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(
                        "Failed to close an unclaimed batch-import owner", failure);
            }
        }

        private void closeUnclaimedAfterFailure(Throwable ownerFailure) {
            try {
                closeUnclaimed();
            } catch (RuntimeException | Error closeFailure) {
                ownerFailure.addSuppressed(closeFailure);
            }
        }
    }

    private static final class TransferState {
        private boolean revoked;
    }

    static final class ActiveOwnerAccess {
        private final BatchImportRepository operations;
        private final Job closeSignal;

        private ActiveOwnerAccess(BatchImportRepository operations, Job closeSignal) {
            this.operations = operations;
            this.closeSignal = closeSignal;
        }

        BatchImportRepository operations() {
            return operations;
        }

        DisposableHandle invokeOnClose(Runnable callback) {
            Objects.requireNonNull(callback, "callback");
            return closeSignal.invokeOnCompletion(
                    failure -> {
                        callback.run();
                        return Unit.INSTANCE;
                    });
        }
    }

    /**
     * Dispatches through the registry on every invocation, so retaining this object never retains
     * the raw repository and cannot outlive the owner identity.
     */
    private static final class RevocableRepository implements BatchImportRepository {
        private final ProductionBatchImportRepositoryLease repositoryLease;

        private RevocableRepository(ProductionBatchImportRepositoryLease repositoryLease) {
            this.repositoryLease = repositoryLease;
        }

        @Override
        public Flow<List<BatchImportJob>> observeBatchImports() {
            return activeRepository().observeBatchImports();
        }

        @Override
        public Object recoverInterruptedBatchImportWork(
                Continuation<? super Unit> continuation) {
            return activeRepository().recoverInterruptedBatchImportWork(continuation);
        }

        @Override
        public Object createBatchImport(
                CreateBatchImportRequest request,
                Continuation<? super BatchImportJob> continuation) {
            return activeRepository().createBatchImport(request, continuation);
        }

        @Override
        public Object createPdfImport(
                CreatePdfImportRequest request,
                Continuation<? super BatchImportJob> continuation) {
            return activeRepository().createPdfImport(request, continuation);
        }

        @Override
        public Object pauseBatchImport(
                String jobId,
                Continuation<? super Unit> continuation) {
            return activeRepository().pauseBatchImport(jobId, continuation);
        }

        @Override
        public Object resumeBatchImport(
                String jobId,
                Continuation<? super Unit> continuation) {
            return activeRepository().resumeBatchImport(jobId, continuation);
        }

        @Override
        public Object retryBatchImportPage(
                String jobId,
                int pageIndex,
                Continuation<? super Unit> continuation) {
            return activeRepository().retryBatchImportPage(jobId, pageIndex, continuation);
        }

        @Override
        public Object skipBatchImportPage(
                String jobId,
                int pageIndex,
                Continuation<? super Unit> continuation) {
            return activeRepository().skipBatchImportPage(jobId, pageIndex, continuation);
        }

        @Override
        public Object prepareOrganization(
                String jobId,
                Continuation<? super BatchImportOrganizationOffer> continuation) {
            return activeRepository().prepareOrganization(jobId, continuation);
        }

        @Override
        public Object organizeBatch(
                BatchImportOrganizationApproval approval,
                Continuation<? super Unit> continuation) {
            return activeRepository().organizeBatch(approval, continuation);
        }

        private BatchImportRepository activeRepository() {
            synchronized (MONITOR) {
                final ProductionBatchImportOwner owner = REPOSITORY_LEASES.get(repositoryLease);
                final ActiveOwner active = OWNERS.get(owner);
                if (active == null ||
                        active.repositoryLease != repositoryLease ||
                        !active.closeSignal.isActive()) {
                    throw new IllegalStateException(
                            "Batch-import repository lease is revoked, forged, or closed");
                }
                return active.repository;
            }
        }
    }

    private static final class ActiveOwner implements AutoCloseable {
        private final BatchImportRepository repository;
        private final Job processingJob;
        private final ProductionBatchImportAuthorityBindings bindings;
        private final CompletableJob closeSignal = JobKt.Job((Job) null);
        private final AtomicBoolean repositoryClaimed = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private ProductionBatchImportRepositoryLease repositoryLease;

        private ActiveOwner(
                BatchImportRepository repository,
                Job processingJob,
                ProductionBatchImportAuthorityBindings bindings) {
            this.repository = Objects.requireNonNull(repository, "repository");
            this.processingJob = Objects.requireNonNull(processingJob, "processingJob");
            this.bindings = Objects.requireNonNull(bindings, "bindings");
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                Throwable failure = null;
                try {
                    closeSignal.cancel(
                            new CancellationException("Batch-import owner was closed"));
                } catch (RuntimeException | Error closeSignalFailure) {
                    failure = closeSignalFailure;
                }
                try {
                    processingJob.cancel(
                            new CancellationException("Batch-import owner was closed"));
                } catch (RuntimeException | Error processingFailure) {
                    if (failure == null) {
                        failure = processingFailure;
                    } else {
                        failure.addSuppressed(processingFailure);
                    }
                }
                if (failure instanceof RuntimeException) {
                    throw (RuntimeException) failure;
                }
                if (failure instanceof Error) {
                    throw (Error) failure;
                }
            }
        }
    }
}
