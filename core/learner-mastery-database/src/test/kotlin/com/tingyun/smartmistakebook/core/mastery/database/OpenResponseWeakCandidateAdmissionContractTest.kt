package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationOutcome
import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.net.URLClassLoader
import java.nio.file.Files
import java.lang.reflect.Modifier
import javax.tools.ToolProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenResponseWeakCandidateAdmissionContractTest {
    @Test
    fun runtimeBoundaryHasNoAcceptedAdmissionMode() {
        assertEquals(
            listOf(LearnerMasteryOpenResponseAdmissionMode.REVIEW_ONLY),
            LearnerMasteryOpenResponseAdmissionMode.entries.toList(),
        )
        assertEquals(
            OpenResponseDedicatedDecisionDisposition.RETAINED_FOR_REVIEW,
            CURRENT_OPEN_RESPONSE_RUNTIME_DISPOSITION,
        )
        assertEquals(
            OpenResponseDedicatedDecisionReason
                .MODEL_SEMANTIC_REVIEW_REQUIRES_NON_MODEL_CONFIRMATION,
            CURRENT_OPEN_RESPONSE_RUNTIME_REASON,
        )
    }

    @Test
    fun runtimeDecisionFactoryCannotRepresentAnAcceptedDecision() {
        val methods =
            Class.forName(
                "com.tingyun.smartmistakebook.core.mastery.database." +
                    "OpenResponseWeakCandidateAdmissionKt",
            ).declaredMethods
        assertFalse(methods.any { it.name == "openResponseDedicatedDecisionEntity" })
        val retainedFactory =
            methods.single { it.name == "retainedOpenResponseDedicatedDecisionEntity" }
        assertFalse(
            retainedFactory.parameterTypes.any { parameterType ->
                parameterType == OpenResponseDedicatedDecisionDisposition::class.java ||
                    parameterType == MasteryEventDirection::class.java ||
                    List::class.java.isAssignableFrom(parameterType)
            },
        )
    }

    @Test
    fun commandCarriesExactHostScopeWithoutModelControlledMasteryFields() {
        val command = command()

        assertEquals(scopeFingerprint(), command.scopeFingerprint)
        assertEquals(
            LearnerMasteryOpenResponseAdmissionMode.REVIEW_ONLY,
            command.admissionMode,
        )
        assertEquals(
            listOf(LearnerMasteryOpenResponseAdmissionMode.REVIEW_ONLY),
            LearnerMasteryOpenResponseAdmissionMode.entries.toList(),
        )
        val exposedNames =
            LearnerMasteryOpenResponseWeakCandidateCommand::class.java.methods
                .map { it.name.lowercase() }
                .toSet()
        listOf(
            "weight",
            "confidence",
            "direction",
            "evidencemass",
            "masteryscore",
            "projection",
            "sql",
            "dao",
        ).forEach { forbidden ->
            assertFalse(
                "Public weak-candidate command exposes $forbidden",
                exposedNames.any { forbidden in it },
            )
        }
    }

    @Test
    fun persistedProofRowsExposeNoAnswerBodyDisplayTextOrNumericModelWeight() {
        val persistedFieldNames =
            listOf(
                MasteryOpenResponseModelEvaluationAttestationEntity::class.java,
                MasteryOpenResponseEvaluationKnowledgeScopeEntity::class.java,
                MasteryOpenResponseDedicatedDecisionEntity::class.java,
            ).flatMap { type -> type.declaredFields.map { it.name.lowercase() } }

        listOf("answer", "prompt", "body", "display", "label", "weight", "confidence", "sql")
            .forEach { forbidden ->
                assertFalse(
                    "Open-response proof chain persists forbidden field $forbidden",
                    persistedFieldNames.any { forbidden in it },
                )
            }
    }

    @Test
    fun malformedScopeAndUnscorableOutputNeverBecomeWeakCandidates() {
        assertThrows(IllegalArgumentException::class.java) {
            command(scopeFingerprint = fingerprint("wrong-scope"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            command(outcome = OpenResponseEvaluationOutcome.UNSCORABLE)
        }
    }

    @Test
    fun authorizationAndRuntimeGrantExposeNoPublicIssuerOrSealAbi() {
        assertTrue(
            LearnerMasteryOpenResponseWeakCandidateAuthorization::class.java
                .constructors
                .isEmpty(),
        )
        assertTrue(
            LearnerMasteryOpenResponseOwnerGrant::class.java.constructors.isEmpty(),
        )
        assertFalse(
            LearnerMasteryOpenResponseWeakCandidateAuthorization::class.java
                .declaredClasses
                .any { it.simpleName == "Companion" },
        )
        val publicGrantSurface =
            LearnerMasteryOpenResponseOwnerGrant::class.java.methods
                .filter { it.declaringClass == LearnerMasteryOpenResponseOwnerGrant::class.java }
        assertTrue(publicGrantSurface.isEmpty())

        val privateIssuer =
            CoreDataLearnerMasteryOwnerBridge::class.java.declaredClasses
                .single { it.simpleName == "OpenResponsePrivateIssuer" }
        assertTrue(Modifier.isPrivate(privateIssuer.modifiers))
        assertTrue(
            privateIssuer.declaredFields
                .filter { it.name.contains("seal", ignoreCase = true) }
                .all { Modifier.isPrivate(it.modifiers) },
        )
        assertTrue(
            privateIssuer.declaredMethods
                .filter {
                    it.name.contains("issue", ignoreCase = true) ||
                        it.name.contains("bind", ignoreCase = true) ||
                        it.name.contains("grant", ignoreCase = true)
                }
                .none { Modifier.isPublic(it.modifiers) },
        )
        val productionIssuerMethodNames =
            (
                CoreDataLearnerMasteryOwnerBridge::class.java.declaredMethods +
                    privateIssuer.declaredMethods
                ).map { it.name.lowercase() }
        assertTrue(
            "Production owner issuer must not retain a test signing or runtime-grant path",
            productionIssuerMethodNames.none { "fortest" in it || "testonly" in it },
        )
        assertTrue(
            "Production runtime records must not manufacture a test generation",
            privateIssuer.declaredClasses
                .flatMap { it.declaredMethods.toList() }
                .none { method ->
                    method.name.contains("test", ignoreCase = true)
                },
        )
    }

    @Test
    fun separatelyCompiledSamePackageClassCannotForgeGrantOrAuthorization() {
        val compiler = ToolProvider.getSystemJavaCompiler()
        assertNotNull("A JDK compiler is required for the ABI forgery test", compiler)
        val root = Files.createTempDirectory("open-response-forgery")
        try {
            val sourceDir =
                root.resolve(
                    "com/tingyun/smartmistakebook/core/mastery/database",
                )
            Files.createDirectories(sourceDir)
            val source = sourceDir.resolve("ExternalSamePackageForger.java")
            Files.writeString(
                source,
                """
                package com.tingyun.smartmistakebook.core.mastery.database;

                public final class ExternalSamePackageForger {
                    public static Object forgeGrant() {
                        return new LearnerMasteryOpenResponseOwnerGrant(new Object());
                    }

                    public static Object forgeAuthorization() {
                        return new LearnerMasteryOpenResponseWeakCandidateAuthorization(
                            new Object(),
                            null,
                            "learner",
                            "scope",
                            () -> 1L,
                            1L
                        );
                    }
                }
                """.trimIndent(),
            )
            val result =
                compiler.run(
                    null,
                    null,
                    null,
                    "-classpath",
                    System.getProperty("java.class.path"),
                    "-d",
                    root.toString(),
                    source.toString(),
                )
            assertEquals(0, result)
            URLClassLoader(arrayOf(root.toUri().toURL()), javaClass.classLoader).use { loader ->
                val forger =
                    loader.loadClass(
                        "com.tingyun.smartmistakebook.core.mastery.database." +
                            "ExternalSamePackageForger",
                    )
                listOf("forgeGrant", "forgeAuthorization").forEach { methodName ->
                    val failure =
                        assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
                            forger.getMethod(methodName).invoke(null)
                        }
                    assertTrue(
                        failure.targetException is SecurityException ||
                            failure.targetException is IllegalAccessError,
                    )
                }
            }
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun productionBridgeIsPackagePrivateAndOwnerSurfaceHasNoDatabaseEscapeHatch() {
        val open =
            CoreDataLearnerMasteryOwnerBridge::class.java.declaredMethods
                .single { it.name == "openOpenResponseWeakCandidateOwner" }
        val bind =
            CoreDataLearnerMasteryOwnerBridge::class.java.declaredMethods
                .single { it.name == "bindOpenResponseWeakCandidateAuthorization" }
        val claim =
            CoreDataLearnerMasteryOwnerBridge::class.java.declaredMethods
                .single { it.name == "claimOpenResponseOwnerGrant" }
        assertFalse(Modifier.isPublic(open.modifiers))
        assertFalse(Modifier.isPublic(bind.modifiers))
        assertFalse(Modifier.isPublic(claim.modifiers))

        val publicSignatures =
            LearnerMasteryOpenResponseWeakCandidateOwner::class.java.methods
                .filter {
                    it.declaringClass ==
                        LearnerMasteryOpenResponseWeakCandidateOwner::class.java
                }
                .joinToString("|") { method ->
                    buildString {
                        append(method.name)
                        append(method.returnType.name)
                        method.parameterTypes.forEach { append(it.name) }
                    }.lowercase()
                }
        listOf("dao", "room", "sqlite", "sql", "projection", "masteryscore", "knowledge")
            .forEach { forbidden ->
                assertFalse(
                    "Open-response owner leaks $forbidden",
                    forbidden in publicSignatures,
                )
            }
    }

    private fun command(
        scopeFingerprint: String = scopeFingerprint(),
        outcome: OpenResponseEvaluationOutcome = OpenResponseEvaluationOutcome.INCORRECT,
    ): LearnerMasteryOpenResponseWeakCandidateCommand =
        LearnerMasteryOpenResponseWeakCandidateCommand(
            learnerId = LEARNER_ID,
            subject = SubjectKind.MATH,
            sourceFactId = "source-fact",
            reviewCaseId = "review-case",
            scopeFingerprint = scopeFingerprint,
            conversationId = "conversation",
            conversationGeneration = 4L,
            conversationStateVersion = 7L,
            questionDocumentId = "question-document",
            questionRevisionNumber = 3,
            questionFingerprint = fingerprint("question"),
            responseBinding = fingerprint("answer-binding"),
            evidenceRequestId = "evidence-request",
            turnReferenceId = "turn-reference",
            turnOrdinal = 5,
            turnGeneration = 6L,
            modeVersion = 8L,
            requestVersion = 9L,
            attemptOrdinal = 2,
            hintCount = 1,
            answerWasRevealed = false,
            modelTaskRequestId = "model-task-request",
            modelResponseSchemaVersion = 1,
            evaluatorRequestVersion = 9L,
            candidateIdempotencyKey = fingerprint("candidate-idempotency"),
            revisionOfCandidateIdempotencyKey = null,
            evidenceFingerprint = fingerprint("evidence"),
            modelOutputFingerprint = fingerprint("model-output"),
            modelVersion = "gpt-5.6-luna",
            outcome = outcome,
            authorizedKnowledgeScope = emptyList(),
            occurredAtEpochMillis = 10_000L,
        )

    private fun scopeFingerprint(): String =
        CanonicalSha256("current-open-response-learning-scope-v1")
            .field("learnerId", LEARNER_ID)
            .field("conversationId", "conversation")
            .field("conversationGeneration", 4L)
            .field("conversationStateVersion", 7L)
            .field("questionDocumentId", "question-document")
            .field("questionRevisionNumber", 3)
            .field("subject", SubjectKind.MATH.name)
            .field("questionFingerprint", fingerprint("question"))
            .field("responseBinding", fingerprint("answer-binding"))
            .field("evidenceRequestId", "evidence-request")
            .field("modeVersion", 8L)
            .field("turnReferenceId", "turn-reference")
            .field("turnOrdinal", 5)
            .field("turnGeneration", 6L)
            .field("attemptOrdinal", 2)
            .field("hintCount", 1)
            .field("answerWasRevealed", false)
            .field("requestVersion", 9L)
            .finish()

    private fun fingerprint(seed: String): String =
        CanonicalSha256("open-response-contract-test")
            .field("seed", seed)
            .finish()

    private companion object {
        const val LEARNER_ID = "learner-open-response-contract"
    }
}
