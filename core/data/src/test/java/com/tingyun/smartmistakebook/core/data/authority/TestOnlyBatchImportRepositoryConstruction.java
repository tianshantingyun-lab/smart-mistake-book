package com.tingyun.smartmistakebook.core.data.authority;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import com.tingyun.smartmistakebook.core.data.capture.CaptureDraftSessionPort;
import com.tingyun.smartmistakebook.core.data.capture.ProductionCaptureSessionPort;
import com.tingyun.smartmistakebook.core.data.session.BatchImportSessionPort;
import com.tingyun.smartmistakebook.core.data.session.SessionScope;
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Test-classpath-only issuer for repository behavior tests.
 *
 * <p>The fixture goes through the same identity registry as production. It does not mint a raw
 * repository or a forged construction-resource shell.
 */
public final class TestOnlyBatchImportRepositoryConstruction {
    private TestOnlyBatchImportRepositoryConstruction() {}

    public static Fixture issue(
            SessionScope scope,
            BatchImportSessionPort batchSessions,
            CaptureDraftSessionPort captureDrafts,
            ModelTaskRepository modelTasks) {
        final Context context = allocateIdentityContext();
        final ProductionCaptureSessionPort learnerBoundCapture =
                learnerBoundCapture(scope.getLearnerId(), captureDrafts);
        final Object generationIdentity = new Object();
        final Object learnerIdentity = new Object();
        final Object sessionOwnerIdentity = new Object();
        final Object modelOwnerIdentity = new Object();
        final CurrentGenerationBatchImportConstructionClaim claim =
                ProductionBatchImportConstructionRegistry.issue(
                        context,
                        scope,
                        batchSessions,
                        learnerBoundCapture,
                        modelTasks,
                        ProductionBatchImportAuthorityBindings.of(
                                generationIdentity,
                                context,
                                learnerIdentity,
                                sessionOwnerIdentity,
                                modelOwnerIdentity));
        return new Fixture(
                claim,
                context,
                learnerBoundCapture,
                generationIdentity,
                learnerIdentity,
                sessionOwnerIdentity,
                modelOwnerIdentity);
    }

    private static ProductionCaptureSessionPort learnerBoundCapture(
            String learnerId,
            CaptureDraftSessionPort delegate) {
        return (ProductionCaptureSessionPort)
                Proxy.newProxyInstance(
                        ProductionCaptureSessionPort.class.getClassLoader(),
                        new Class<?>[] {ProductionCaptureSessionPort.class},
                        (receiver, method, arguments) -> {
                            if (method.getName().equals("getLearnerId")) {
                                return learnerId;
                            }
                            if (method.getName().equals("toString")) {
                                return "TestOnlyLearnerBoundCapture";
                            }
                            if (method.getName().equals("hashCode")) {
                                return System.identityHashCode(receiver);
                            }
                            if (method.getName().equals("equals")) {
                                return receiver == arguments[0];
                            }
                            try {
                                return method.invoke(delegate, arguments);
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
    }

    private static Context allocateIdentityContext() {
        final IdentityContext context = allocateWithoutConstructor(IdentityContext.class);
        context.initialize(
                allocateWithoutConstructor(IdentityContentResolver.class),
                new File(
                        System.getProperty("java.io.tmpdir"),
                        "smart-mistake-book-batch-import-test-" +
                                Integer.toHexString(System.identityHashCode(context))));
        return context;
    }

    private static <T> T allocateWithoutConstructor(Class<T> type) {
        try {
            final Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
            final Field unsafeField = unsafeType.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            final Object unsafe = unsafeField.get(null);
            final Method allocateInstance = unsafeType.getMethod("allocateInstance", Class.class);
            return type.cast(allocateInstance.invoke(unsafe, type));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not allocate the identity-only test Context", failure);
        }
    }

    private static final class IdentityContext extends ContextWrapper {
        private ContentResolver resolver;
        private File filesDirectory;

        private IdentityContext() {
            super(null);
        }

        private void initialize(ContentResolver resolver, File filesDirectory) {
            this.resolver = resolver;
            this.filesDirectory = filesDirectory;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public ContentResolver getContentResolver() {
            return resolver;
        }

        @Override
        public File getFilesDir() {
            return filesDirectory;
        }

        @Override
        public String getPackageName() {
            return "com.tingyun.smartmistakebook.test";
        }
    }

    /** Constructor-free resolver used only as a non-null identity in local JVM tests. */
    private static final class IdentityContentResolver extends ContentResolver {
        private IdentityContentResolver(Context context) {
            super(context);
        }
    }

    public static final class Fixture implements AutoCloseable {
        private final CurrentGenerationBatchImportConstructionClaim claim;
        private final Context context;
        private final CaptureDraftSessionPort captureDrafts;
        private final Object generationIdentity;
        private final Object learnerIdentity;
        private final Object sessionOwnerIdentity;
        private final Object modelOwnerIdentity;

        private Fixture(
                CurrentGenerationBatchImportConstructionClaim claim,
                Context context,
                CaptureDraftSessionPort captureDrafts,
                Object generationIdentity,
                Object learnerIdentity,
                Object sessionOwnerIdentity,
                Object modelOwnerIdentity) {
            this.claim = claim;
            this.context = context;
            this.captureDrafts = captureDrafts;
            this.generationIdentity = generationIdentity;
            this.learnerIdentity = learnerIdentity;
            this.sessionOwnerIdentity = sessionOwnerIdentity;
            this.modelOwnerIdentity = modelOwnerIdentity;
        }

        public CurrentGenerationBatchImportConstructionClaim claim() {
            return claim;
        }

        public Context context() {
            return context;
        }

        public CaptureDraftSessionPort captureDrafts() {
            return captureDrafts;
        }

        public Object generationIdentity() {
            return generationIdentity;
        }

        public Object learnerIdentity() {
            return learnerIdentity;
        }

        public Object sessionOwnerIdentity() {
            return sessionOwnerIdentity;
        }

        public Object modelOwnerIdentity() {
            return modelOwnerIdentity;
        }

        @Override
        public void close() {
            claim.close();
        }
    }
}
