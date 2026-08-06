package com.tingyun.smartmistakebook.core.data.production;

/** One-shot transfer object accepted by the application publication graph. */
public abstract class CompleteProductionCapabilityAssembly implements AutoCloseable {
    private CompleteProductionCapabilityAssembly() {}

    /**
     * Consumes an authenticated complete owner-port claim.
     *
     * <p>A reflectively created claim has no registry identity and is rejected. The release
     * artifact intentionally has no claim issuer while the current-generation owner is
     * incomplete.
     */
    public static CompleteProductionCapabilityAssembly fromCurrentGenerationClaim(
            CompleteProductionOwnerPortClaim claim) {
        return ProductionCapabilityPublicationRegistry.transferOwnerClaim(claim);
    }

    public abstract ProductionCapabilitySnapshot claimForPublication();

    @Override
    public abstract void close();

    static CompleteProductionCapabilityAssembly newRegistryShell() {
        return new Issued();
    }

    private static final class Issued extends CompleteProductionCapabilityAssembly {
        private Issued() {}

        @Override
        public ProductionCapabilitySnapshot claimForPublication() {
            return ProductionCapabilityPublicationRegistry.transferAssembly(this);
        }

        @Override
        public void close() {
            ProductionCapabilityPublicationRegistry.closeAssembly(this);
        }
    }
}
