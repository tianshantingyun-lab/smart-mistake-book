package com.tingyun.smartmistakebook.core.data.authority;

/** Opaque one-shot hand-off of authenticated current-generation construction resources. */
public abstract class CurrentGenerationBatchImportConstructionClaim implements AutoCloseable {
    private CurrentGenerationBatchImportConstructionClaim() {}

    public abstract CurrentGenerationBatchImportConstructionResources claimForConstruction();

    @Override
    public abstract void close();

    static CurrentGenerationBatchImportConstructionClaim newRegistryShell() {
        return new Issued();
    }

    private static final class Issued extends CurrentGenerationBatchImportConstructionClaim {
        private Issued() {}

        @Override
        public CurrentGenerationBatchImportConstructionResources claimForConstruction() {
            return ProductionBatchImportConstructionRegistry.transferClaim(this);
        }

        @Override
        public void close() {
            ProductionBatchImportConstructionRegistry.closeClaim(this);
        }
    }
}
