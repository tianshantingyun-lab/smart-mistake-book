package com.tingyun.smartmistakebook.core.student.mistake.database

import android.content.Context
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef

internal class StoreBackedStudentMistakeModelReadPort(
    private val learnerId: String,
    private val store: StudentMistakeStore,
) : StudentMistakeModelReadPort {
    init {
        learnerId.requireStoreText("Model-read owner learner id", MAX_ID_CHARS)
    }

    override suspend fun readProblemSummaries(
        query: StudentMistakeModelReadQuery,
    ): List<StudentMistakeModelProblemSummary> {
        val knowledgeScopes: List<KnowledgeNodeRef?> =
            if (query.knowledgeNodes.isEmpty()) {
                listOf(null)
            } else {
                query.knowledgeNodes.sortedWith(
                    compareBy(
                        { it.knowledgeNodeId },
                        { it.taxonomyVersion },
                        { it.knowledgePackVersion },
                    ),
                )
            }
        return knowledgeScopes
            .flatMap { knowledgeNode ->
                store.searchMistakes(
                    StudentMistakeSearchQuery(
                        learnerId = learnerId,
                        subject = query.subject,
                        text = query.text,
                        knowledgeNode = knowledgeNode,
                        limit = query.limit,
                    ),
                ).items
            }
            .filter { item ->
                item.problemRevision.problem.learnerId == learnerId &&
                    item.problemRevision.problem.subject == query.subject
            }
            .distinctBy { item -> item.problemRevision.canonicalFingerprint }
            .sortedWith(
                compareByDescending<StudentMistakeSearchItem> {
                    it.changedAtEpochMillis
                }.thenBy { it.problemRevision.problem.problemId },
            )
            .take(query.limit)
            .map { item ->
                StudentMistakeModelProblemSummary(
                    problemRevision = item.problemRevision,
                    title = item.title,
                    stemPreview = item.stemPreview,
                    practiceUnitTitle = item.practiceUnitTitle,
                    estimatedDurationSeconds = item.estimatedDurationSeconds,
                )
            }
    }

    override fun close() {
        store.close()
    }
}

internal object StudentMistakeModelReadPortFactory {
    fun open(
        context: Context,
        learnerId: String,
        ownerKey: StudentMistakeOwnerKey,
    ): StudentMistakeModelReadPort =
        StoreBackedStudentMistakeModelReadPort(
            learnerId = learnerId,
            store =
                StudentMistakeOwnedDatabase.openStore(
                    context,
                    KnowledgeReferenceProofAuthority.create().verifier,
                    ownerKey,
                ),
        )
}
