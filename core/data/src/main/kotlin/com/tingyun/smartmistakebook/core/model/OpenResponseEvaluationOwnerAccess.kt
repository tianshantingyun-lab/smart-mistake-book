package com.tingyun.smartmistakebook.core.model

import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofVerifier
import java.util.function.BooleanSupplier
import java.util.function.LongSupplier
import kotlin.jvm.JvmSynthetic

/** Core:data's sole Kotlin entry to the package-private model authority bridge. */
@JvmSynthetic
internal fun openCoreDataOpenResponseEvaluationTaskProducer(
    learnerId: String,
    conversationId: String,
    questionDocumentId: String,
    questionRevisionNumber: Int,
    subject: SubjectKind,
    questionDocument: QuestionDocument,
    rubricCanonicalFingerprint: String,
    operationBinding: String,
    currentAnswer: String,
    teachingReferences: List<VerifiedOpenResponseEvaluationTeachingReference>,
    evaluator: OpenResponseEvaluatorKind,
    evaluatorPolicyFingerprint: String,
    knowledgeReferenceVerifier: KnowledgeReferenceProofVerifier,
    expiresAtEpochMillis: Long,
    nowEpochMillis: LongSupplier,
    scopeIsCurrent: BooleanSupplier,
): OpenResponseEvaluationTaskProducer =
    CoreDataOpenResponseEvaluationOwnerBridge.openProducer(
        learnerId,
        conversationId,
        questionDocumentId,
        questionRevisionNumber,
        subject,
        questionDocument,
        rubricCanonicalFingerprint,
        operationBinding,
        currentAnswer,
        teachingReferences,
        evaluator,
        evaluatorPolicyFingerprint,
        knowledgeReferenceVerifier,
        expiresAtEpochMillis,
        nowEpochMillis,
        scopeIsCurrent,
    )
