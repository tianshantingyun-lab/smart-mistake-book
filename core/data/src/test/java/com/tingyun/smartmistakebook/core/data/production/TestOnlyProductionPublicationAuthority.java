package com.tingyun.smartmistakebook.core.data.production;

import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Map;

/** Test-classpath-only issuer. No corresponding issuer exists in main or release sources. */
final class TestOnlyProductionPublicationAuthority {
    private static final int SLOT_COUNT = ProductionAdapter.values().length;

    private TestOnlyProductionPublicationAuthority() {}

    static CompleteProductionOwnerPortClaim issue(
            IssuedProductionCapabilityLeases leases,
            Object generation,
            Object context,
            Object learner,
            Object knowledge,
            Object terminalFence) {
        return issueWithBindings(
                leases,
                repeated(generation),
                repeated(context),
                repeated(learner),
                repeated(knowledge),
                repeated(terminalFence));
    }

    static CompleteProductionOwnerPortClaim issueWithBindings(
            IssuedProductionCapabilityLeases leases,
            Object[] generations,
            Object[] contexts,
            Object[] learners,
            Object[] knowledge,
            Object[] terminalFences) {
        try {
            final Class<?> registry = ProductionCapabilityPublicationRegistry.class;
            final Class<?> publicationType =
                    Class.forName(registry.getName() + "$RegisteredPublication");
            final Constructor<?> publicationConstructor =
                    publicationType.getDeclaredConstructor(
                            IssuedProductionCapabilityLeases.class,
                            Object[].class,
                            Object[].class,
                            Object[].class,
                            Object[].class,
                            Object[].class);
            publicationConstructor.setAccessible(true);
            final Object publication =
                    publicationConstructor.newInstance(
                            leases,
                            generations,
                            contexts,
                            learners,
                            knowledge,
                            terminalFences);

            final Class<?> claimType =
                    Class.forName(CompleteProductionOwnerPortClaim.class.getName() + "$Issued");
            final Constructor<?> claimConstructor = claimType.getDeclaredConstructor();
            claimConstructor.setAccessible(true);
            final CompleteProductionOwnerPortClaim claim =
                    (CompleteProductionOwnerPortClaim) claimConstructor.newInstance();

            final Field monitorField = registry.getDeclaredField("MONITOR");
            final Field claimsField = registry.getDeclaredField("OWNER_CLAIMS");
            monitorField.setAccessible(true);
            claimsField.setAccessible(true);
            final Object monitor = monitorField.get(null);
            @SuppressWarnings("unchecked")
            final Map<CompleteProductionOwnerPortClaim, Object> claims =
                    (Map<CompleteProductionOwnerPortClaim, Object>) claimsField.get(null);
            synchronized (monitor) {
                if (claims.put(claim, publication) != null) {
                    throw new IllegalStateException("Test claim identity was reused");
                }
            }
            return claim;
        } catch (InvocationTargetException failure) {
            return rethrow(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Test-only publication authority no longer matches release", failure);
        }
    }

    private static Object[] repeated(Object identity) {
        final Object[] bindings = new Object[SLOT_COUNT];
        Arrays.fill(bindings, identity);
        return bindings;
    }

    private static <Result> Result rethrow(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new AssertionError(failure);
    }
}
