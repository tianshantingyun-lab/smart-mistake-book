package com.tingyun.smartmistakebook.core.data.authority;

import android.content.Context;
import com.tingyun.smartmistakebook.core.data.capture.CaptureDraftSessionPort;
import com.tingyun.smartmistakebook.core.data.session.BatchImportSessionPort;
import com.tingyun.smartmistakebook.core.data.session.SessionScope;
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository;
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository;
import kotlinx.coroutines.Job;

/**
 * Short-lived construction capability. Every method is backed by registry identity; a reflected
 * shell has no resources and cannot register a final claim.
 */
public abstract class CurrentGenerationBatchImportConstructionResources
        implements AutoCloseable {
    private CurrentGenerationBatchImportConstructionResources() {}

    public abstract Context getCanonicalContext();

    public abstract SessionScope getSessionScope();

    public abstract BatchImportSessionPort getBatchSessions();

    public abstract CaptureDraftSessionPort getCaptureDrafts();

    public abstract ModelTaskRepository getModelTaskQueue();

    public abstract Object getGenerationIdentity();

    public abstract Object getContextIdentity();

    public abstract Object getLearnerIdentity();

    public abstract Object getSessionOwnerIdentity();

    public abstract Object getModelOwnerIdentity();

    /** Consumes the sole permission to invoke the raw private constructor. */
    public abstract void claimForRawConstruction();

    public abstract CurrentGenerationBatchImportClaim registerBuilt(
            BatchImportRepository repository,
            Job processingJob,
            Object generationIdentity,
            Object contextIdentity,
            Object learnerIdentity,
            Object sessionOwnerIdentity,
            Object modelOwnerIdentity);

    @Override
    public abstract void close();

    static CurrentGenerationBatchImportConstructionResources newRegistryShell() {
        return new Issued();
    }

    private static final class Issued extends CurrentGenerationBatchImportConstructionResources {
        private Issued() {}

        @Override
        public Context getCanonicalContext() {
            return ProductionBatchImportConstructionRegistry.active(this).canonicalContext();
        }

        @Override
        public SessionScope getSessionScope() {
            return ProductionBatchImportConstructionRegistry.active(this).sessionScope();
        }

        @Override
        public BatchImportSessionPort getBatchSessions() {
            return ProductionBatchImportConstructionRegistry.active(this).batchSessions();
        }

        @Override
        public CaptureDraftSessionPort getCaptureDrafts() {
            return ProductionBatchImportConstructionRegistry.active(this).captureDrafts();
        }

        @Override
        public ModelTaskRepository getModelTaskQueue() {
            return ProductionBatchImportConstructionRegistry.active(this).modelTaskQueue();
        }

        @Override
        public Object getGenerationIdentity() {
            return ProductionBatchImportConstructionRegistry.active(this)
                    .bindings()
                    .generation();
        }

        @Override
        public Object getContextIdentity() {
            return ProductionBatchImportConstructionRegistry.active(this).bindings().context();
        }

        @Override
        public Object getLearnerIdentity() {
            return ProductionBatchImportConstructionRegistry.active(this).bindings().learner();
        }

        @Override
        public Object getSessionOwnerIdentity() {
            return ProductionBatchImportConstructionRegistry.active(this)
                    .bindings()
                    .sessionOwner();
        }

        @Override
        public Object getModelOwnerIdentity() {
            return ProductionBatchImportConstructionRegistry.active(this)
                    .bindings()
                    .modelOwner();
        }

        @Override
        public void claimForRawConstruction() {
            ProductionBatchImportConstructionRegistry.claimRawConstruction(this);
        }

        @Override
        public CurrentGenerationBatchImportClaim registerBuilt(
                BatchImportRepository repository,
                Job processingJob,
                Object generationIdentity,
                Object contextIdentity,
                Object learnerIdentity,
                Object sessionOwnerIdentity,
                Object modelOwnerIdentity) {
            return ProductionBatchImportConstructionRegistry.registerBuilt(
                    this,
                    repository,
                    processingJob,
                    ProductionBatchImportAuthorityBindings.of(
                            generationIdentity,
                            contextIdentity,
                            learnerIdentity,
                            sessionOwnerIdentity,
                            modelOwnerIdentity));
        }

        @Override
        public void close() {
            ProductionBatchImportConstructionRegistry.closeResources(this);
        }
    }
}
