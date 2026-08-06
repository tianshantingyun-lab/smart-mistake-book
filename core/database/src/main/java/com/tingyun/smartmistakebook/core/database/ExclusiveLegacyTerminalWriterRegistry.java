package com.tingyun.smartmistakebook.core.database;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Process-local ownership for the one terminal migration writer of a canonical legacy database.
 *
 * <p>The class is deliberately package-private. Creating another registry cannot mint database
 * authority because the production registry and the resource opener both remain private to the
 * database owner file.
 */
final class ExclusiveLegacyTerminalWriterRegistry<K> {
    private final Set<K> activeKeys = new HashSet<>();

    <R extends AutoCloseable> OwnedLease<R> acquire(
            K key,
            ResourceFactory<R> resourceFactory
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(resourceFactory, "resourceFactory");

        WriterClaim<K> writerClaim = claim(key);
        try {
            R resourceLease = Objects.requireNonNull(
                    resourceFactory.open(),
                    "resourceFactory returned null"
            );
            return new OwnedLease<>(resourceLease, writerClaim);
        } catch (RuntimeException | Error failure) {
            try {
                writerClaim.close();
            } catch (Throwable releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }

    private WriterClaim<K> claim(K key) {
        synchronized (activeKeys) {
            if (!activeKeys.add(key)) {
                throw new IllegalStateException(
                        "The canonical legacy database already has an active terminal writer"
                );
            }
        }
        return new WriterClaim<>(key, this);
    }

    private void release(K key) {
        synchronized (activeKeys) {
            if (!activeKeys.remove(key)) {
                throw new IllegalStateException(
                        "The terminal writer claim is not active"
                );
            }
        }
    }

    @FunctionalInterface
    interface ResourceFactory<R> {
        R open();
    }

    static final class OwnedLease<R extends AutoCloseable> implements AutoCloseable {
        private final R resourceLease;
        private final WriterClaim<?> writerClaim;
        private boolean closed;

        private OwnedLease(
                R resourceLease,
                WriterClaim<?> writerClaim
        ) {
            this.resourceLease = resourceLease;
            this.writerClaim = writerClaim;
        }

        synchronized R resourceLease() {
            if (closed) {
                throw new IllegalStateException("The terminal writer lease is closed");
            }
            return resourceLease;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
            }

            Throwable resourceFailure = null;
            try {
                resourceLease.close();
            } catch (Throwable failure) {
                resourceFailure = failure;
            } finally {
                try {
                    writerClaim.close();
                } catch (Throwable releaseFailure) {
                    if (resourceFailure != null) {
                        resourceFailure.addSuppressed(releaseFailure);
                    } else if (releaseFailure instanceof RuntimeException) {
                        throw (RuntimeException) releaseFailure;
                    } else if (releaseFailure instanceof Error) {
                        throw (Error) releaseFailure;
                    } else {
                        throw new IllegalStateException(
                                "Releasing the terminal writer claim failed",
                                releaseFailure
                        );
                    }
                }
            }

            if (resourceFailure instanceof RuntimeException) {
                throw (RuntimeException) resourceFailure;
            }
            if (resourceFailure instanceof Error) {
                throw (Error) resourceFailure;
            }
            if (resourceFailure != null) {
                throw new IllegalStateException(
                        "Closing the canonical legacy database resource failed",
                        resourceFailure
                );
            }
        }
    }

    private static final class WriterClaim<K> implements AutoCloseable {
        private final K key;
        private final ExclusiveLegacyTerminalWriterRegistry<K> registry;
        private boolean closed;

        private WriterClaim(
                K key,
                ExclusiveLegacyTerminalWriterRegistry<K> registry
        ) {
            this.key = key;
            this.registry = registry;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            registry.release(key);
        }
    }
}
