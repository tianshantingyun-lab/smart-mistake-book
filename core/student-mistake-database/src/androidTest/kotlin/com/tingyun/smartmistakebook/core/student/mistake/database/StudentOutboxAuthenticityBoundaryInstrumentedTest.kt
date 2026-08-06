package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.storage.StudentOutboxAuthenticityProof
import java.lang.reflect.Modifier
import javax.crypto.SecretKey
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudentOutboxAuthenticityBoundaryInstrumentedTest {
    @Test
    fun androidRuntimeBindsVerifierToAnOwnerPrivateOperation() {
        val authenticator =
            StudentOutboxAuthenticator(
                "learner-android-operation-boundary",
                ActiveStudentOutboxAuthenticityKey(
                    keyId = "student-outbox-auth-key:android-boundary",
                    keyVersion = StudentOutboxAuthenticator.KEY_VERSION,
                    keyAlias = TEST_KEY_ALIAS,
                    algorithmVersion = StudentOutboxAuthenticityProof.ALGORITHM_VERSION,
                    sourceStoreGeneration = "student-generation-android-boundary",
                    relayEpoch = "relay-epoch-android-boundary",
                ),
                NoAccessStudentOutboxHmacKeyStore,
            )
        val operationField =
            StudentOutboxAuthenticityVerifier::class.java.getDeclaredField("operation")
        operationField.isAccessible = true
        val operation = operationField.get(authenticator.verifier)

        assertFalse(Modifier.isPublic(operation.javaClass.modifiers))
        assertTrue(Modifier.isPrivate(operation.javaClass.modifiers))
    }

    @Test
    fun androidRuntimeDoesNotPublishSigningOrSecretKeyApi() {
        listOf(
            StudentOutboxAuthenticator::class.java,
            StudentOutboxAuthenticatorSession::class.java,
            StudentOutboxAuthenticityIssuer::class.java,
            StudentOutboxHmacKeyStore::class.java,
            AndroidKeystoreStudentOutboxHmacKeyStore::class.java,
        ).forEach { type ->
            assertFalse(Modifier.isPublic(type.modifiers))
            assertTrue(
                type.declaredConstructors.none { constructor ->
                    Modifier.isPublic(constructor.modifiers) ||
                        Modifier.isProtected(constructor.modifiers)
                },
            )
            assertTrue(
                type.declaredMethods.none { method ->
                    val exported =
                        Modifier.isPublic(method.modifiers) ||
                            Modifier.isProtected(method.modifiers)
                    exported &&
                        (
                            method.returnType == SecretKey::class.java ||
                                method.parameterTypes.any { it == SecretKey::class.java }
                            )
                },
            )
        }
    }
}

private object NoAccessStudentOutboxHmacKeyStore : StudentOutboxHmacKeyStore() {
    override fun contains(keyAlias: String): Boolean = false

    override fun loadOrCreateBootstrap(keyAlias: String) =
        error("Boundary construction must not access a key")

    override fun issueHmac(
        keyAlias: String,
        authenticatedContextFingerprint: String,
    ): String = error("Boundary construction must not sign")

    override fun issueReviewResponseBinding(
        keyAlias: String,
        authenticatedBindingContextFingerprint: String,
    ): String = error("Boundary construction must not sign a review response")
}

private const val TEST_KEY_ALIAS =
    "com.tingyun.smartmistakebook.student.outbox.authenticity.hmac.android-boundary"
