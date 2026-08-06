package com.tingyun.smartmistakebook.core.database;

import android.content.Context;

import com.tingyun.smartmistakebook.core.data.authority.AuthorityCutoverJournal;
import com.tingyun.smartmistakebook.core.data.authority.LegacyAuthorityCutoverJournalAdapter;

import java.util.Objects;

/**
 * Package-private bridge from the database owner's hidden terminal lease to one high-level
 * core:data migration session.
 *
 * <p>Neither the database handle, the database ports, nor the terminal write-barrier capability
 * leaves this JVM package. The authority layer receives only its append-only journal abstraction,
 * and closing this lease revokes that abstraction at the database-owner boundary.
 */
final class LegacyTerminalCutoverJournalLease implements AutoCloseable {
    private final LegacyTerminalAuthorityBridge bridge;
    private final AuthorityCutoverJournal journal;
    private boolean closed;

    private LegacyTerminalCutoverJournalLease(
            LegacyTerminalAuthorityBridge bridge
    ) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.journal = new LegacyAuthorityCutoverJournalAdapter(bridge.cutoverJournal());
    }

    static LegacyTerminalCutoverJournalLease open(Context context) {
        return new LegacyTerminalCutoverJournalLease(
                LegacyTerminalAuthorityBridgeOpener.open(
                        Objects.requireNonNull(context, "context")
                )
        );
    }

    synchronized AuthorityCutoverJournal journal() {
        if (closed) {
            throw new IllegalStateException("Legacy terminal cutover journal lease is closed");
        }
        return journal;
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
