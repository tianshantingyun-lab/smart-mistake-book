package com.tingyun.smartmistakebook.core.data.authority;

import android.content.Context;
import android.content.ContextWrapper;
import com.tingyun.smartmistakebook.core.data.capture.ProductionCaptureSessionPort;
import com.tingyun.smartmistakebook.core.data.session.BatchImportSessionPort;
import com.tingyun.smartmistakebook.core.data.session.SessionScope;
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Test-classpath-only issuer for exercising the construction capability boundary. */
final class TestOnlyBatchImportConstructionAuthority {
    private TestOnlyBatchImportConstructionAuthority() {}

    static Fixture issue(String learnerId) {
        return issueWithCaptureLearner(learnerId, learnerId);
    }

    static Fixture issueWithCaptureLearner(
            String sessionLearnerId,
            String captureLearnerId) {
        final Context canonicalContext = allocateContextWithoutAndroidRuntime();
        final Object generationIdentity = new Object();
        final Object learnerIdentity = new Object();
        final Object sessionOwnerIdentity = new Object();
        final Object modelOwnerIdentity = new Object();
        final BatchImportSessionPort batchSessions =
                interfaceProxy(BatchImportSessionPort.class, sessionLearnerId);
        final ProductionCaptureSessionPort captureDrafts =
                interfaceProxy(ProductionCaptureSessionPort.class, captureLearnerId);
        final ModelTaskRepository modelTaskQueue =
                interfaceProxy(ModelTaskRepository.class, sessionLearnerId);
        final CurrentGenerationBatchImportConstructionClaim claim =
                ProductionBatchImportConstructionRegistry.issue(
                        canonicalContext,
                        new SessionScope(sessionLearnerId),
                        batchSessions,
                        captureDrafts,
                        modelTaskQueue,
                        ProductionBatchImportAuthorityBindings.of(
                                generationIdentity,
                                canonicalContext,
                                learnerIdentity,
                                sessionOwnerIdentity,
                                modelOwnerIdentity));
        return new Fixture(
                claim,
                canonicalContext,
                generationIdentity,
                learnerIdentity,
                sessionOwnerIdentity,
                modelOwnerIdentity);
    }

    private static Context allocateContextWithoutAndroidRuntime() {
        try {
            final Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            final Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            final Object unsafe = unsafeField.get(null);
            final Method allocateInstance =
                    unsafeType.getMethod("allocateInstance", Class.class);
            return (Context) allocateInstance.invoke(unsafe, ContextWrapper.class);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not allocate the identity-only test Context", failure);
        }
    }

    private static <Capability> Capability interfaceProxy(
            Class<Capability> capabilityType,
            String learnerId) {
        final Object proxy =
                Proxy.newProxyInstance(
                        capabilityType.getClassLoader(),
                        new Class<?>[] {capabilityType},
                        (receiver, method, arguments) -> {
                            if (method.getName().equals("getLearnerId")) {
                                return learnerId;
                            }
                            if (method.getName().equals("toString")) {
                                return "TestOnly" + capabilityType.getSimpleName();
                            }
                            if (method.getName().equals("hashCode")) {
                                return System.identityHashCode(receiver);
                            }
                            if (method.getName().equals("equals")) {
                                return receiver == arguments[0];
                            }
                            throw new AssertionError(
                                    "Construction fixture unexpectedly invoked " + method);
                        });
        return capabilityType.cast(proxy);
    }

    static final class Fixture {
        private final CurrentGenerationBatchImportConstructionClaim claim;
        private final Context contextIdentity;
        private final Object generationIdentity;
        private final Object learnerIdentity;
        private final Object sessionOwnerIdentity;
        private final Object modelOwnerIdentity;

        private Fixture(
                CurrentGenerationBatchImportConstructionClaim claim,
                Context contextIdentity,
                Object generationIdentity,
                Object learnerIdentity,
                Object sessionOwnerIdentity,
                Object modelOwnerIdentity) {
            this.claim = claim;
            this.contextIdentity = contextIdentity;
            this.generationIdentity = generationIdentity;
            this.learnerIdentity = learnerIdentity;
            this.sessionOwnerIdentity = sessionOwnerIdentity;
            this.modelOwnerIdentity = modelOwnerIdentity;
        }

        CurrentGenerationBatchImportConstructionClaim claim() {
            return claim;
        }

        Context contextIdentity() {
            return contextIdentity;
        }

        Object generationIdentity() {
            return generationIdentity;
        }

        Object learnerIdentity() {
            return learnerIdentity;
        }

        Object sessionOwnerIdentity() {
            return sessionOwnerIdentity;
        }

        Object modelOwnerIdentity() {
            return modelOwnerIdentity;
        }
    }
}
