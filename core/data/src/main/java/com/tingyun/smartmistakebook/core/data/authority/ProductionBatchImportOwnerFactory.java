package com.tingyun.smartmistakebook.core.data.authority;

/** Consumes only an authenticated current-generation claim; it accepts no caller-owned ports. */
public final class ProductionBatchImportOwnerFactory {
    private ProductionBatchImportOwnerFactory() {}

    public static ProductionBatchImportOwner open(
            CurrentGenerationBatchImportClaim currentGenerationClaim) {
        return ProductionBatchImportOwnerRegistry.transfer(currentGenerationClaim);
    }
}
