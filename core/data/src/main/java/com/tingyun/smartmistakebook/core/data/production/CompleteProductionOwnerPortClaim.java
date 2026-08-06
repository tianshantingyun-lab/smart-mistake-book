package com.tingyun.smartmistakebook.core.data.production;

/**
 * Opaque, process-local ownership claim for one complete current-generation capability set.
 *
 * <p>Only the package-private current-generation handoff may ask the registry to issue this claim.
 * Reflectively constructing the private shell is harmless because every operation also requires
 * registry identity.
 */
public abstract class CompleteProductionOwnerPortClaim implements AutoCloseable {
    private CompleteProductionOwnerPortClaim() {}

    @Override
    public abstract void close();

    static CompleteProductionOwnerPortClaim newRegistryShell() {
        return new Issued();
    }

    private static final class Issued extends CompleteProductionOwnerPortClaim {
        private Issued() {}

        @Override
        public void close() {
            ProductionCapabilityPublicationRegistry.closeOwnerClaim(this);
        }
    }
}
