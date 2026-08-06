package com.tingyun.smartmistakebook.core.database;

import android.content.Context;

import com.tingyun.smartmistakebook.core.data.authority.DatabaseThreeAuthorityLegacyBusinessWriteBarrierOwner;
import com.tingyun.smartmistakebook.core.data.authority.ThreeAuthorityLegacyBusinessWriteBarrierOwner;

import java.util.Objects;

/**
 * Hidden, exclusive handoff for the post-proof legacy barrier transition.
 *
 * <p>The authority layer receives only the proof-consuming high-level owner. The raw journal
 * writer and write-barrier database ports remain inside this JVM package and are revoked when the
 * lease closes.
 */
final class LegacyTerminalBarrierHandoffLease implements AutoCloseable {
    private final LegacyTerminalAuthorityBridge bridge;
    private final ThreeAuthorityLegacyBusinessWriteBarrierOwner owner;
    private boolean closed;

    private LegacyTerminalBarrierHandoffLease(LegacyTerminalAuthorityBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.owner =
                new DatabaseThreeAuthorityLegacyBusinessWriteBarrierOwner(
                        bridge.writeBarrier(), bridge.cutoverJournal());
    }

    static LegacyTerminalBarrierHandoffLease open(Context context) {
        return new LegacyTerminalBarrierHandoffLease(
                LegacyTerminalAuthorityBridgeOpener.open(
                        Objects.requireNonNull(context, "context")));
    }

    synchronized ThreeAuthorityLegacyBusinessWriteBarrierOwner owner() {
        if (closed) {
            throw new IllegalStateException("Legacy terminal barrier handoff is closed");
        }
        return owner;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        bridge.close();
    }
}
