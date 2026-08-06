package com.tingyun.smartmistakebook.core.data.authority;

import com.tingyun.smartmistakebook.core.domain.BatchImportRepository;

/** Current-generation lifecycle owner for the learner-bound batch-import composite. */
public abstract class ProductionBatchImportOwner implements AutoCloseable {
    private ProductionBatchImportOwner() {}

    /** Transfers the one repository lease owned by this composite. */
    public abstract BatchImportRepository claimRepository();

    @Override
    public abstract void close();

    static ProductionBatchImportOwner newRegistryShell() {
        return new Issued();
    }

    private static final class Issued extends ProductionBatchImportOwner {
        private Issued() {}

        @Override
        public BatchImportRepository claimRepository() {
            return ProductionBatchImportOwnerRegistry.claimRepository(this);
        }

        @Override
        public void close() {
            ProductionBatchImportOwnerRegistry.closeOwner(this);
        }
    }
}
