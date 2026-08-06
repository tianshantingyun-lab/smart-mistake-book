package com.tingyun.smartmistakebook.core.model

import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.BooleanSupplier
import java.util.function.LongSupplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenResponseEvaluationProductionBridgeTest {
    @Test
    fun exactScopeProducerCreatesAuthorizedTaskWithoutDisclosingLocalIdentity() {
        val fixture = Fixture()
        val producer = fixture.producer()

        val input = producer.createTask(requestVersion = 4)
        input.requireCurrentHostAuthorization()
        val authorizedScope = input.authorizedScopeForProvider()

        assertEquals(SubjectKind.MATH, input.subject)
        assertEquals(QUESTION, input.questionDocument)
        assertEquals(ANSWER, input.currentAnswer)
        assertEquals(1, input.teachingGuidance.size)
        assertEquals(producer.scopeFingerprint, input.subjectId)
        listOf(
            LEARNER_ID,
            CONVERSATION_ID,
            QUESTION_DOCUMENT_ID,
            "student-mistakes.db",
            "learner-mastery.db",
            "sql",
            "dao",
        ).forEach { forbidden ->
            assertFalse(
                "Authorized scope leaked $forbidden",
                authorizedScope.contains(forbidden, ignoreCase = true),
            )
        }
    }

    @Test
    fun existingTaskFailsClosedAfterScopeChangeRevocationAndExpiry() {
        val scopeFixture = Fixture()
        val scopeProducer = scopeFixture.producer()
        val scopeInput = scopeProducer.createTask(1)
        scopeFixture.current.set(false)
        assertThrows(IllegalStateException::class.java) {
            scopeInput.requireCurrentHostAuthorization()
        }

        val revokedFixture = Fixture()
        val revokedProducer = revokedFixture.producer()
        val revokedInput = revokedProducer.createTask(1)
        revokedProducer.revoke()
        assertThrows(IllegalStateException::class.java) {
            revokedInput.authorizedScopeForProvider()
        }

        val expiryFixture = Fixture()
        val expiryProducer = expiryFixture.producer(expiresAtEpochMillis = 2_000)
        val expiryInput = expiryProducer.createTask(1)
        expiryFixture.now.set(2_000)
        assertThrows(IllegalStateException::class.java) {
            expiryInput.requireCurrentHostAuthorization()
        }
    }

    @Test
    fun foreignOrCrossSubjectKnowledgeAndBackwardRequestVersionFailClosed() {
        val fixture = Fixture()
        val foreignAuthority = KnowledgeReferenceProofAuthority.create()
        val foreignProof = fixture.proof(foreignAuthority, SubjectKind.MATH)
        assertThrows(IllegalArgumentException::class.java) {
            fixture.producer(
                teachingProof = foreignProof,
                verifier = fixture.knowledgeAuthority.verifier,
            )
        }

        val crossSubjectProof = fixture.proof(fixture.knowledgeAuthority, SubjectKind.PHYSICS)
        assertThrows(IllegalArgumentException::class.java) {
            fixture.producer(teachingProof = crossSubjectProof)
        }

        val producer = fixture.producer()
        producer.createTask(8)
        assertThrows(IllegalArgumentException::class.java) {
            producer.createTask(7)
        }
    }

    @Test
    fun publicAbiCannotCreateAuthorityOrReachTheOwnerBridge() {
        val bridge = CoreDataOpenResponseEvaluationOwnerBridge::class.java
        val ownerKey = OpenResponseEvaluationOwnerKey::class.java
        assertFalse(Modifier.isPublic(bridge.modifiers))
        assertFalse(Modifier.isPublic(ownerKey.modifiers))
        assertTrue(
            bridge.declaredMethods.all { method ->
                !Modifier.isPublic(method.modifiers) &&
                    !Modifier.isProtected(method.modifiers)
            },
        )
        assertTrue(
            bridge.declaredFields.all { field -> Modifier.isPrivate(field.modifiers) },
        )
        assertTrue(
            ownerKey.declaredConstructors.all { constructor ->
                Modifier.isPrivate(constructor.modifiers)
            },
        )

        val createMethods =
            OpenResponseEvaluationHostAuthority.Companion::class.java.declaredMethods
                .filter { method -> method.name == "create" }
        assertTrue(createMethods.isNotEmpty())
        assertTrue(
            createMethods.all { method ->
                ownerKey in method.parameterTypes &&
                    method.parameterTypes.isNotEmpty()
            },
        )
        val issueMethods =
            HostIssuedOpenResponseEvaluationRequest.Companion::class.java.declaredMethods
                .filter { method -> method.name == "issue" }
        assertTrue(issueMethods.isNotEmpty())
        assertTrue(
            issueMethods.all { method ->
                ownerKey in method.parameterTypes &&
                    method.parameterTypes.isNotEmpty()
            },
        )

        val producerApi =
            OpenResponseEvaluationTaskProducer::class.java.methods
                .joinToString(separator = "\n") { method ->
                    buildString {
                        append(method.returnType.name)
                        method.parameterTypes.forEach { append(it.name) }
                    }
                }
                .lowercase()
        listOf(
            "hostauthority",
            "hostissued",
            "ownerkey",
            "sql",
            "dao",
            "database",
            "mastery",
        ).forEach { forbidden ->
            assertFalse("Producer ABI leaked $forbidden", forbidden in producerApi)
        }
    }

    private class Fixture {
        val now = AtomicLong(1_000)
        val current = AtomicBoolean(true)
        val knowledgeAuthority = KnowledgeReferenceProofAuthority.create()

        fun producer(
            teachingProof: KnowledgeReferenceProofAuthority.Proof =
                proof(knowledgeAuthority, SubjectKind.MATH),
            verifier: KnowledgeReferenceProofAuthority.Verifier =
                knowledgeAuthority.verifier,
            expiresAtEpochMillis: Long = 10_000,
        ): OpenResponseEvaluationTaskProducer =
            CoreDataOpenResponseEvaluationOwnerBridge.openProducer(
                LEARNER_ID,
                CONVERSATION_ID,
                QUESTION_DOCUMENT_ID,
                3,
                SubjectKind.MATH,
                QUESTION,
                fingerprint("rubric"),
                fingerprint("operation-binding"),
                ANSWER,
                listOf(
                    VerifiedOpenResponseEvaluationTeachingReference(
                        proof = teachingProof,
                        label = "根据导数符号判断单调性",
                        constraint = TutorTeachingConstraint.EXPLAIN_DIRECTLY,
                    ),
                ),
                OpenResponseEvaluatorKind.RUBRIC,
                fingerprint("policy"),
                verifier,
                expiresAtEpochMillis,
                LongSupplier(now::get),
                BooleanSupplier(current::get),
            )

        fun proof(
            authority: KnowledgeReferenceProofAuthority,
            subject: SubjectKind,
        ): KnowledgeReferenceProofAuthority.Proof =
            authority.issuer.issue(
                KnowledgeNodeRef(
                    subject = subject,
                    knowledgeNodeId = "derivative-monotonicity",
                    taxonomyVersion = "taxonomy-v1",
                    knowledgePackVersion = "pack-v1",
                ),
                fingerprint("manifest"),
                1,
            )
    }

    private companion object {
        const val LEARNER_ID = "local-learner"
        const val CONVERSATION_ID = "conversation-7"
        const val QUESTION_DOCUMENT_ID = "question-11"
        const val ANSWER = "函数在该区间单调递减。"
        val QUESTION =
            QuestionDocument(
                id = "question-content",
                blocks =
                    listOf(
                        ContentBlock.Paragraph(
                            id = "question-paragraph",
                            markdown = "已知函数在区间内导数为正，判断函数的单调性。",
                        ),
                    ),
            )

        fun fingerprint(seed: String): String =
            CanonicalSha256("open-response-production-bridge-test")
                .field("seed", seed)
                .finish()
    }
}
