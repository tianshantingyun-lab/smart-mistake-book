package com.tingyun.smartmistakebook.core.data.production;

import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapter;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Identity owner for the complete-claim -> assembly -> snapshot publication chain. */
final class ProductionCapabilityPublicationRegistry {
    private static final int SLOT_COUNT = ProductionAdapter.values().length;
    private static final Object MONITOR = new Object();
    private static final Map<CompleteProductionOwnerPortClaim, RegisteredPublication>
            OWNER_CLAIMS = new IdentityHashMap<>();
    private static final Map<CompleteProductionCapabilityAssembly, RegisteredPublication>
            ASSEMBLIES = new IdentityHashMap<>();
    private static final Map<ProductionCapabilitySnapshot, RegisteredPublication>
            SNAPSHOTS = new IdentityHashMap<>();

    private ProductionCapabilityPublicationRegistry() {}

    static CompleteProductionOwnerPortClaim issueCurrentGeneration(
            CurrentGenerationProductionPublicationHandoff handoff) {
        Objects.requireNonNull(handoff, "handoff");
        final CurrentGenerationProductionPublicationHandoff.State state = handoff.claim();
        final RegisteredPublication publication;
        try {
            final Object[] bindings = repeatedBinding(state.authorityBinding);
            publication =
                    new RegisteredPublication(
                            state.leases,
                            bindings,
                            bindings,
                            bindings,
                            bindings,
                            bindings);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(state.leases, failure);
            throw failure;
        }

        CompleteProductionOwnerPortClaim claim = null;
        try {
            claim = CompleteProductionOwnerPortClaim.newRegistryShell();
            synchronized (MONITOR) {
                if (OWNER_CLAIMS.put(claim, publication) != null) {
                    throw new IllegalStateException("Complete owner claim identity was reused");
                }
            }
            return claim;
        } catch (RuntimeException | Error failure) {
            if (claim != null) {
                synchronized (MONITOR) {
                    OWNER_CLAIMS.remove(claim);
                }
            }
            closeAfterFailure(publication, failure);
            throw failure;
        }
    }

    static CompleteProductionCapabilityAssembly transferOwnerClaim(
            CompleteProductionOwnerPortClaim claim) {
        Objects.requireNonNull(claim, "claim");
        final RegisteredPublication publication;
        synchronized (MONITOR) {
            publication = OWNER_CLAIMS.remove(claim);
            if (publication == null) {
                throw new IllegalStateException(
                        "Complete owner-port claim is revoked, forged, or already claimed");
            }
        }

        CompleteProductionCapabilityAssembly assembly = null;
        try {
            assembly = CompleteProductionCapabilityAssembly.newRegistryShell();
            synchronized (MONITOR) {
                if (ASSEMBLIES.put(assembly, publication) != null) {
                    throw new IllegalStateException("Capability assembly identity was reused");
                }
            }
            return assembly;
        } catch (RuntimeException | Error failure) {
            if (assembly != null) {
                synchronized (MONITOR) {
                    ASSEMBLIES.remove(assembly);
                }
            }
            closeAfterFailure(publication, failure);
            throw failure;
        }
    }

    static ProductionCapabilitySnapshot transferAssembly(
            CompleteProductionCapabilityAssembly assembly) {
        Objects.requireNonNull(assembly, "assembly");
        final RegisteredPublication publication;
        synchronized (MONITOR) {
            publication = ASSEMBLIES.remove(assembly);
            if (publication == null) {
                throw new IllegalStateException(
                        "Production capability assembly is revoked, forged, or already claimed");
            }
        }

        ProductionCapabilitySnapshot snapshot = null;
        try {
            snapshot = ProductionCapabilitySnapshot.newRegistryShell();
            synchronized (MONITOR) {
                if (SNAPSHOTS.put(snapshot, publication) != null) {
                    throw new IllegalStateException("Capability snapshot identity was reused");
                }
            }
            return snapshot;
        } catch (RuntimeException | Error failure) {
            if (snapshot != null) {
                synchronized (MONITOR) {
                    SNAPSHOTS.remove(snapshot);
                }
            }
            closeAfterFailure(publication, failure);
            throw failure;
        }
    }

    static IssuedProductionCapabilityLeases activeSnapshot(
            ProductionCapabilitySnapshot snapshot) {
        synchronized (MONITOR) {
            final RegisteredPublication publication = SNAPSHOTS.get(snapshot);
            if (publication == null) {
                throw new IllegalStateException(
                        "Production capability snapshot is revoked, forged, or closed");
            }
            return publication.leases;
        }
    }

    static void closeOwnerClaim(CompleteProductionOwnerPortClaim claim) {
        closeRemoved(removeIdentity(OWNER_CLAIMS, claim));
    }

    static void closeAssembly(CompleteProductionCapabilityAssembly assembly) {
        closeRemoved(removeIdentity(ASSEMBLIES, assembly));
    }

    static void closeSnapshot(ProductionCapabilitySnapshot snapshot) {
        closeRemoved(removeIdentity(SNAPSHOTS, snapshot));
    }

    private static <Identity> RegisteredPublication removeIdentity(
            Map<Identity, RegisteredPublication> registry,
            Identity identity) {
        synchronized (MONITOR) {
            return registry.remove(identity);
        }
    }

    private static void closeRemoved(RegisteredPublication publication) {
        if (publication != null) {
            publication.close();
        }
    }

    private static void closeAfterFailure(
            RegisteredPublication publication,
            Throwable ownerFailure) {
        try {
            publication.close();
        } catch (RuntimeException | Error closeFailure) {
            ownerFailure.addSuppressed(closeFailure);
        }
    }

    private static void closeAfterFailure(
            IssuedProductionCapabilityLeases leases,
            Throwable ownerFailure) {
        try {
            leases.closeReverse();
        } catch (RuntimeException | Error closeFailure) {
            ownerFailure.addSuppressed(closeFailure);
        }
    }

    private static Object[] repeatedBinding(Object binding) {
        final Object[] bindings = new Object[SLOT_COUNT];
        Arrays.fill(bindings, Objects.requireNonNull(binding, "authority binding"));
        return bindings;
    }

    /**
     * Registry payload retained only inside this class. The sole issuer first consumes an
     * authenticated current-generation handoff; it never accepts caller-provided binding arrays.
     */
    private static final class RegisteredPublication implements AutoCloseable {
        private final IssuedProductionCapabilityLeases leases;
        @SuppressWarnings("unused")
        private final Object generationBinding;
        @SuppressWarnings("unused")
        private final Object contextBinding;
        @SuppressWarnings("unused")
        private final Object learnerBinding;
        @SuppressWarnings("unused")
        private final Object knowledgeBinding;
        @SuppressWarnings("unused")
        private final Object terminalFenceBinding;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private RegisteredPublication(
                IssuedProductionCapabilityLeases leases,
                Object[] generationBindings,
                Object[] contextBindings,
                Object[] learnerBindings,
                Object[] knowledgeBindings,
                Object[] terminalFenceBindings) {
            this.leases = Objects.requireNonNull(leases, "leases");
            requireCompleteDistinctLeases(leases);
            generationBinding = requireOneBinding("generation", generationBindings);
            contextBinding = requireOneBinding("context", contextBindings);
            learnerBinding = requireOneBinding("learner", learnerBindings);
            knowledgeBinding = requireOneBinding("knowledge", knowledgeBindings);
            terminalFenceBinding = requireOneBinding("terminal fence", terminalFenceBindings);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                leases.closeReverse();
            }
        }

        private static Object requireOneBinding(String name, Object[] bindings) {
            if (bindings == null || bindings.length != SLOT_COUNT) {
                throw new IllegalArgumentException(
                        "Every production slot must carry one " + name + " binding");
            }
            final Object expected = Objects.requireNonNull(bindings[0], name + " binding");
            for (Object binding : bindings) {
                if (binding != expected) {
                    throw new IllegalArgumentException(
                            "All production slots must share the same " + name + " binding");
                }
            }
            return expected;
        }

        private static void requireCompleteDistinctLeases(
                IssuedProductionCapabilityLeases leases) {
            final List<Object> ordered =
                    Arrays.asList(
                            leases.getCaptureWorkflow(),
                            leases.getBatchImport(),
                            leases.getMistakeDetail(),
                            leases.getMistakeOrganization(),
                            leases.getStudentMistakeCatalog(),
                            leases.getTutorSession(),
                            leases.getTutorConversationLobby(),
                            leases.getTutorMasteryAndProfile(),
                            leases.getTutorTeachingReference(),
                            leases.getModelTaskQueue(),
                            leases.getModelAssetDocuments(),
                            leases.getReviewPlanning(),
                            leases.getWorkManagerCoordination(),
                            leases.getTerminalCutoverGate());
            final Set<Object> identities =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object lease : ordered) {
                if (!identities.add(Objects.requireNonNull(lease, "production lease"))) {
                    throw new IllegalArgumentException(
                            "Every production slot must own a distinct lease");
                }
            }
            if (identities.size() != SLOT_COUNT) {
                throw new IllegalArgumentException(
                        "Production publication requires every capability inventory slot");
            }
        }
    }
}
