package com.tingyun.smartmistakebook.core.data.authority;

import com.tingyun.smartmistakebook.core.domain.BatchImportRepository;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import kotlinx.coroutines.Job;

/** Test-classpath-only fixture issuer for exercising owner-transfer races. */
final class TestOnlyProductionBatchImportAuthority {
    private TestOnlyProductionBatchImportAuthority() {}

    static CurrentGenerationBatchImportClaim issue(
            BatchImportRepository repository,
            Job processingJob,
            Object generationIdentity,
            Object contextIdentity,
            Object learnerIdentity,
            Object sessionOwnerIdentity,
            Object modelOwnerIdentity) {
        return issueInternal(
                repository,
                processingJob,
                generationIdentity,
                contextIdentity,
                learnerIdentity,
                sessionOwnerIdentity,
                modelOwnerIdentity,
                learnerIdentity,
                null,
                null);
    }

    static CurrentGenerationBatchImportClaim issueWithBuiltLearner(
            BatchImportRepository repository,
            Job processingJob,
            Object generationIdentity,
            Object contextIdentity,
            Object learnerIdentity,
            Object sessionOwnerIdentity,
            Object modelOwnerIdentity,
            Object builtLearnerIdentity) {
        return issueInternal(
                repository,
                processingJob,
                generationIdentity,
                contextIdentity,
                learnerIdentity,
                sessionOwnerIdentity,
                modelOwnerIdentity,
                builtLearnerIdentity,
                null,
                null);
    }

    static CurrentGenerationBatchImportClaim issueBlocking(
            BatchImportRepository repository,
            Job processingJob,
            Object generationIdentity,
            Object contextIdentity,
            Object learnerIdentity,
            Object sessionOwnerIdentity,
            Object modelOwnerIdentity,
            CountDownLatch buildStarted,
            CountDownLatch releaseBuild) {
        return issueInternal(
                repository,
                processingJob,
                generationIdentity,
                contextIdentity,
                learnerIdentity,
                sessionOwnerIdentity,
                modelOwnerIdentity,
                learnerIdentity,
                buildStarted,
                releaseBuild);
    }

    private static CurrentGenerationBatchImportClaim issueInternal(
            BatchImportRepository repository,
            Job processingJob,
            Object generationIdentity,
            Object contextIdentity,
            Object learnerIdentity,
            Object sessionOwnerIdentity,
            Object modelOwnerIdentity,
            Object builtLearnerIdentity,
            CountDownLatch buildStarted,
            CountDownLatch releaseBuild) {
        try {
            final Class<?> registry = ProductionBatchImportOwnerRegistry.class;
            final Class<?> activeOwnerType =
                    Class.forName(registry.getName() + "$ActiveOwner");
            final Constructor<?> activeOwnerConstructor =
                    activeOwnerType.getDeclaredConstructor(
                            BatchImportRepository.class,
                            Job.class,
                            ProductionBatchImportAuthorityBindings.class);
            activeOwnerConstructor.setAccessible(true);
            final ProductionBatchImportAuthorityBindings expectedBindings =
                    ProductionBatchImportAuthorityBindings.of(
                            generationIdentity,
                            contextIdentity,
                            learnerIdentity,
                            sessionOwnerIdentity,
                            modelOwnerIdentity);
            final ProductionBatchImportAuthorityBindings builtBindings =
                    ProductionBatchImportAuthorityBindings.of(
                            generationIdentity,
                            contextIdentity,
                            builtLearnerIdentity,
                            sessionOwnerIdentity,
                            modelOwnerIdentity);

            final Class<?> builderType =
                    Class.forName(registry.getName() + "$CompositeBuilder");
            final Object builder =
                    Proxy.newProxyInstance(
                            builderType.getClassLoader(),
                            new Class<?>[] {builderType},
                            (proxy, method, arguments) -> {
                                if (method.getName().equals("build")) {
                                    if (buildStarted != null) {
                                        buildStarted.countDown();
                                    }
                                    if (releaseBuild != null) {
                                        try {
                                            releaseBuild.await();
                                        } catch (InterruptedException interrupted) {
                                            Thread.currentThread().interrupt();
                                            throw new IllegalStateException(
                                                    "Test batch-import build was interrupted",
                                                    interrupted);
                                        }
                                    }
                                    return activeOwnerConstructor.newInstance(
                                            repository,
                                            processingJob,
                                            builtBindings);
                                }
                                if (method.getName().equals("toString")) {
                                    return "TestOnlyBatchImportCompositeBuilder";
                                }
                                if (method.getName().equals("hashCode")) {
                                    return System.identityHashCode(proxy);
                                }
                                if (method.getName().equals("equals")) {
                                    return proxy == arguments[0];
                                }
                                throw new AssertionError("Unexpected builder method: " + method);
                            });

            final Class<?> resourcesType =
                    Class.forName(registry.getName() + "$RegisteredResources");
            final Constructor<?> resourcesConstructor =
                    resourcesType.getDeclaredConstructor(
                            ProductionBatchImportAuthorityBindings.class,
                            builderType,
                            AutoCloseable.class);
            resourcesConstructor.setAccessible(true);
            final Object resources =
                    resourcesConstructor.newInstance(
                            expectedBindings,
                            builder,
                            (AutoCloseable)
                                    () ->
                                            processingJob.cancel(
                                                    new CancellationException(
                                                            "Unclaimed test batch-import owner was closed")));

            final Class<?> claimType =
                    Class.forName(CurrentGenerationBatchImportClaim.class.getName() + "$Issued");
            final Constructor<?> claimConstructor = claimType.getDeclaredConstructor();
            claimConstructor.setAccessible(true);
            final CurrentGenerationBatchImportClaim claim =
                    (CurrentGenerationBatchImportClaim) claimConstructor.newInstance();

            final Field monitorField = registry.getDeclaredField("MONITOR");
            final Field claimsField = registry.getDeclaredField("CLAIMS");
            monitorField.setAccessible(true);
            claimsField.setAccessible(true);
            final Object monitor = monitorField.get(null);
            @SuppressWarnings("unchecked")
            final Map<CurrentGenerationBatchImportClaim, Object> claims =
                    (Map<CurrentGenerationBatchImportClaim, Object>) claimsField.get(null);
            synchronized (monitor) {
                if (claims.put(claim, resources) != null) {
                    throw new IllegalStateException("Test claim identity was reused");
                }
            }
            return claim;
        } catch (InvocationTargetException failure) {
            return rethrow(failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(
                    "Test-only batch-import authority no longer matches release", failure);
        }
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
