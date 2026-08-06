package com.tingyun.smartmistakebook.core.data.authority;

import com.tingyun.smartmistakebook.core.domain.BatchImportRepository;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Owns the one batch-import composite published by a learning-authority runtime. */
final class RuntimeOwnedProductionBatchImport implements AutoCloseable {
    private final AtomicBoolean attachStarted = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<ProductionBatchImportOwner> owner = new AtomicReference<>();

    BatchImportRepository attach(CurrentGenerationBatchImportClaim claim) {
        Objects.requireNonNull(claim, "currentGenerationClaim");
        if (!attachStarted.compareAndSet(false, true)) {
            claim.close();
            throw new IllegalStateException("Batch-import owner was already attached");
        }
        if (closed.get()) {
            claim.close();
            throw new IllegalStateException("Batch-import runtime is closed");
        }

        ProductionBatchImportOwner candidate = null;
        try {
            candidate = ProductionBatchImportOwnerFactory.open(claim);
            final BatchImportRepository repository = candidate.claimRepository();
            if (!owner.compareAndSet(null, candidate)) {
                throw new IllegalStateException("Batch-import owner was already published");
            }
            if (closed.get()) {
                final ProductionBatchImportOwner published = owner.getAndSet(null);
                if (published != null) {
                    published.close();
                }
                throw new IllegalStateException("Batch-import runtime closed during publication");
            }
            candidate = null;
            return repository;
        } finally {
            claim.close();
            if (candidate != null) {
                candidate.close();
            }
        }
    }

    @Override
    public void close() {
        closed.set(true);
        final ProductionBatchImportOwner owned = owner.getAndSet(null);
        if (owned != null) {
            owned.close();
        }
    }
}
