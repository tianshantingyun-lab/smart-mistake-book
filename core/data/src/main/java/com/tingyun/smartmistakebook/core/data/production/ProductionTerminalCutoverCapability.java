package com.tingyun.smartmistakebook.core.data.production;

/**
 * Zero-field terminal slot. Its presence proves only that the complete publication crossed the
 * terminal gate.
 */
public abstract class ProductionTerminalCutoverCapability {
    private ProductionTerminalCutoverCapability() {}

    static ProductionTerminalCutoverCapability issue() {
        return new Issued();
    }

    private static final class Issued extends ProductionTerminalCutoverCapability {
        private Issued() {}
    }
}
