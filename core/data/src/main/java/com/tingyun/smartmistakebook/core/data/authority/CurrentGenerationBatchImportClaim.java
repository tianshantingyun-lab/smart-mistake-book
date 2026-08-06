package com.tingyun.smartmistakebook.core.data.authority;

/**
 * Opaque one-shot claim for a batch-import composite bound to one current authority generation.
 *
 * <p>Only the authenticated current-generation construction registry can issue this shell.
 * Reflectively constructing the private shell does not create registry identity.
 */
public abstract class CurrentGenerationBatchImportClaim implements AutoCloseable {
    private CurrentGenerationBatchImportClaim() {}

    static CurrentGenerationBatchImportClaim newRegistryShell() {
        return new Issued();
    }

    @Override
    public abstract void close();

    private static final class Issued extends CurrentGenerationBatchImportClaim {
        private Issued() {}

        @Override
        public void close() {
            ProductionBatchImportOwnerRegistry.closeClaim(this);
        }
    }
}
