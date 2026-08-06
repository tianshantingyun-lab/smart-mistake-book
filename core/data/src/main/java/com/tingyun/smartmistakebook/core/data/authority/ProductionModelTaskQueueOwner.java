package com.tingyun.smartmistakebook.core.data.authority;

import com.tingyun.smartmistakebook.core.domain.ConfiguredModelExecutionLease;
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository;
import com.tingyun.smartmistakebook.core.domain.RestrictedModelAssetSource;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Current-generation model queue plus the lease that revokes its provider execution. */
public final class ProductionModelTaskQueueOwner implements AutoCloseable {
    private final ModelTaskRepository modelTaskQueue;
    private final RestrictedModelAssetSource modelAssetDocuments;
    private final ConfiguredModelExecutionLease executionLease;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private ProductionModelTaskQueueOwner(
            ModelTaskRepository modelTaskQueue,
            RestrictedModelAssetSource modelAssetDocuments,
            ConfiguredModelExecutionLease executionLease) {
        this.modelTaskQueue = Objects.requireNonNull(modelTaskQueue, "modelTaskQueue");
        this.modelAssetDocuments =
                Objects.requireNonNull(modelAssetDocuments, "modelAssetDocuments");
        this.executionLease = Objects.requireNonNull(executionLease, "executionLease");
    }

    static ProductionModelTaskQueueOwner issue(
            ModelTaskRepository modelTaskQueue,
            RestrictedModelAssetSource modelAssetDocuments,
            ConfiguredModelExecutionLease executionLease) {
        return new ProductionModelTaskQueueOwner(
                modelTaskQueue, modelAssetDocuments, executionLease);
    }

    public ModelTaskRepository getModelTaskQueue() {
        return modelTaskQueue;
    }

    public RestrictedModelAssetSource getModelAssetDocuments() {
        return modelAssetDocuments;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            executionLease.close();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Failed to revoke model execution lease", failure);
        }
    }
}
