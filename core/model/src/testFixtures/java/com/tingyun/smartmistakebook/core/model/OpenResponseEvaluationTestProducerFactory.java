package com.tingyun.smartmistakebook.core.model;

import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Test-fixture-only access to the module-owned issuer.
 *
 * <p>This class is packaged only in Gradle's test-fixtures artifact. Production consumers never
 * receive it, while provider tests can exercise a genuinely signed request without reflection or
 * widening the production authority API.
 */
public final class OpenResponseEvaluationTestProducerFactory {
    private OpenResponseEvaluationTestProducerFactory() {}

    public static OpenResponseEvaluationTaskProducer open(
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
        return CoreDataOpenResponseEvaluationOwnerBridge.openProducer(
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
