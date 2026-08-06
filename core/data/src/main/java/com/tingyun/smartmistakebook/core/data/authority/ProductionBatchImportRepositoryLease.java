package com.tingyun.smartmistakebook.core.data.authority;

/** Registry-authenticated identity carried only by the revocable repository facade. */
final class ProductionBatchImportRepositoryLease {
    private ProductionBatchImportRepositoryLease() {}

    static ProductionBatchImportRepositoryLease newRegistryShell() {
        return new ProductionBatchImportRepositoryLease();
    }
}
