package com.tingyun.smartmistakebook.core.data.openresponse

import com.tingyun.smartmistakebook.core.data.authority.LearnerBoundLearningEvidencePort
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofVerifier
import java.util.function.LongSupplier

/**
 * Production assembly. The caller must transfer the runtime's one-shot learner-mastery owner;
 * this function never opens a database or manufactures a fallback writer.
 */
internal object CoreDataTutorOpenResponseLearningHostAssembly {
    fun assemble(
        evidence: LearnerBoundLearningEvidencePort,
        evaluator: IndependentOpenResponseEvaluator,
        candidateOwner: LearnerBoundOpenResponseWeakCandidateOwner,
        contextOwner: CurrentTutorOpenResponseContextOwner,
        nowEpochMillis: LongSupplier,
    ): CoreDataTutorOpenResponseLearningAssemblyResult {
        val result = CoreDataTutorOpenResponseLearningHostAdapter.assemble(
            evidence = evidence,
            evaluator = evaluator,
            candidateOwner = candidateOwner,
            contextOwner = contextOwner,
            nowEpochMillis = nowEpochMillis,
        )
        if (result is CoreDataTutorOpenResponseLearningAssemblyResult.Unavailable) {
            (candidateOwner as? AutoCloseable)?.close()
        }
        return result
    }

    fun assemble(
        evidence: LearnerBoundLearningEvidencePort,
        modelTasks: ModelTaskRepository,
        knowledgeReferenceVerifier: KnowledgeReferenceProofVerifier,
        candidateOwner: LearnerBoundOpenResponseWeakCandidateOwner,
        contextOwner: CurrentTutorOpenResponseContextOwner,
        nowEpochMillis: LongSupplier,
    ): CoreDataTutorOpenResponseLearningAssemblyResult =
        assemble(
            evidence = evidence,
            evaluator = ModelTaskRepositoryOpenResponseEvaluator(
                modelTasks = modelTasks,
                knowledgeReferenceVerifier = knowledgeReferenceVerifier,
                nowEpochMillis = nowEpochMillis,
            ),
            candidateOwner = candidateOwner,
            contextOwner = contextOwner,
            nowEpochMillis = nowEpochMillis,
        )
}
