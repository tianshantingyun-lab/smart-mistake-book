package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluatorKind
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorTeachingConstraint
import com.tingyun.smartmistakebook.core.model.VerifiedOpenResponseEvaluationTeachingReference
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.function.LongSupplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProductionOpenResponseEvaluationTaskProducerTest {
    @Test
    fun producerIsBoundToExactCurrentLocalScopeAndRechecksItAfterCreation() {
        val fixture = Fixture()
        val binding = fixture.binding()
        val liveScope = AtomicReference(binding.scopeIdentity)
        val producer =
            fixture.producer(
                binding = binding,
                currentScope =
                    CurrentOpenResponseEvaluationScopeAuthorization { expected ->
                        liveScope.get() == expected
                    },
            )
        val input = producer.createTask(5)

        assertEquals(SubjectKind.MATH, input.subject)
        assertEquals(QUESTION, input.questionDocument)
        assertEquals(ANSWER, input.currentAnswer)

        liveScope.set(binding.scopeIdentity.copy(conversationId = "another-conversation"))
        assertThrows(IllegalStateException::class.java) {
            input.requireCurrentHostAuthorization()
        }
    }

    @Test
    fun learnerQuestionRevisionSubjectRubricAndAnswerMismatchesRejectBeforeIssuance() {
        val fixture = Fixture()
        val binding = fixture.binding()
        val mismatches =
            listOf(
                binding.scopeIdentity.copy(learnerId = "another-learner"),
                binding.scopeIdentity.copy(conversationId = "another-conversation"),
                binding.scopeIdentity.copy(questionDocumentId = "another-question"),
                binding.scopeIdentity.copy(questionRevisionNumber = 4),
                binding.scopeIdentity.copy(subject = SubjectKind.PHYSICS),
                binding.scopeIdentity.copy(questionFingerprint = fingerprint("other-question")),
                binding.scopeIdentity.copy(answerFingerprint = fingerprint("other-answer")),
                binding.scopeIdentity.copy(rubricCanonicalFingerprint = fingerprint("other-rubric")),
            )

        mismatches.forEach { liveScope ->
            assertThrows(IllegalArgumentException::class.java) {
                fixture.producer(
                    binding = binding,
                    currentScope =
                        CurrentOpenResponseEvaluationScopeAuthorization { expected ->
                            liveScope == expected
                        },
                )
            }
        }
    }

    @Test
    fun unverifiedKnowledgeReferenceCannotEnterTheProducer() {
        val fixture = Fixture()
        val foreignAuthority = KnowledgeReferenceProofAuthority.create()
        val foreignProof =
            foreignAuthority.issuer.issue(
                fixture.knowledgeRef,
                fingerprint("foreign-manifest"),
                1,
            )
        val binding =
            fixture.binding(
                teachingProof = foreignProof,
            )

        assertThrows(IllegalArgumentException::class.java) {
            fixture.producer(binding = binding)
        }
    }

    private class Fixture {
        val now = AtomicLong(1_000)
        val knowledgeAuthority = KnowledgeReferenceProofAuthority.create()
        val knowledgeRef =
            KnowledgeNodeRef(
                subject = SubjectKind.MATH,
                knowledgeNodeId = "derivative-monotonicity",
                taxonomyVersion = "taxonomy-v1",
                knowledgePackVersion = "pack-v1",
            )

        fun binding(
            teachingProof: KnowledgeReferenceProofAuthority.Proof =
                knowledgeAuthority.issuer.issue(
                    knowledgeRef,
                    fingerprint("manifest"),
                    1,
                ),
        ): CurrentOpenResponseEvaluationBinding =
            CurrentOpenResponseEvaluationBinding(
                learnerId = "local-learner",
                conversationId = "conversation-7",
                questionDocumentId = "question-11",
                questionRevisionNumber = 3,
                subject = SubjectKind.MATH,
                questionDocument = QUESTION,
                rubricCanonicalFingerprint = fingerprint("rubric"),
                operationBinding = fingerprint("operation-binding"),
                currentAnswer = ANSWER,
                teachingReferences =
                    listOf(
                        VerifiedOpenResponseEvaluationTeachingReference(
                            proof = teachingProof,
                            label = "根据导数符号判断单调性",
                            constraint = TutorTeachingConstraint.EXPLAIN_DIRECTLY,
                        ),
                    ),
                evaluator = OpenResponseEvaluatorKind.RUBRIC,
                evaluatorPolicyFingerprint = fingerprint("policy"),
            )

        fun producer(
            binding: CurrentOpenResponseEvaluationBinding = binding(),
            currentScope: CurrentOpenResponseEvaluationScopeAuthorization =
                CurrentOpenResponseEvaluationScopeAuthorization { expected ->
                    expected == binding.scopeIdentity
                },
        ) = ProductionOpenResponseEvaluationTaskProducerFactory.create(
            binding = binding,
            knowledgeReferenceVerifier = knowledgeAuthority.verifier,
            expiresAtEpochMillis = 10_000,
            nowEpochMillis = LongSupplier(now::get),
            currentScopeAuthorization = currentScope,
        )
    }

    private companion object {
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
            CanonicalSha256("core-data-open-response-production-test")
                .field("seed", seed)
                .finish()
    }
}
