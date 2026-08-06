package com.tingyun.smartmistakebook.core.model;

import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * The sole production bridge from core:data to the module-owned open-response issuer.
 *
 * <p>This class, its methods, and the owner key are package-private. The audited core:data
 * split-package access file can obtain only an exact-scope {@link
 * OpenResponseEvaluationTaskProducer}; it can never obtain the authority root or mint a host
 * request directly.
 */
final class CoreDataOpenResponseEvaluationOwnerBridge {
    private static final OpenResponseEvaluationHostAuthority AUTHORITY =
            OpenResponseEvaluationHostAuthority.Companion.create(
                    OpenResponseEvaluationOwnerKey.INSTANCE);

    private CoreDataOpenResponseEvaluationOwnerBridge() {}

    static OpenResponseEvaluationTaskProducer openProducer(
            String learnerId,
            String conversationId,
            String questionDocumentId,
            int questionRevisionNumber,
            SubjectKind subject,
            QuestionDocument questionDocument,
            String rubricCanonicalFingerprint,
            String operationBinding,
            String currentAnswer,
            List<VerifiedOpenResponseEvaluationTeachingReference> teachingReferences,
            OpenResponseEvaluatorKind evaluator,
            String evaluatorPolicyFingerprint,
            KnowledgeReferenceProofAuthority.Verifier knowledgeReferenceVerifier,
            long expiresAtEpochMillis,
            LongSupplier nowEpochMillis,
            BooleanSupplier scopeIsCurrent) {
        return OpenResponseEvaluationTaskProducerFactory.INSTANCE.open(
                AUTHORITY,
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
                scopeIsCurrent);
    }
}
