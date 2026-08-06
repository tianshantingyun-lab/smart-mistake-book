package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import kotlinx.coroutines.flow.Flow

data class StudentMistakeEntryId(
    val value: String,
) {
    init {
        value.requireStoreText("Error-book entry id", MAX_ID_CHARS)
    }
}

data class StudentMistakeCurriculumSectionKey(
    val value: String,
) {
    init {
        value.requireStoreText("Curriculum-section key", MAX_ID_CHARS)
    }
}

data class StudentMistakeLibraryFilter(
    val subject: SubjectKind? = null,
    val text: String? = null,
    val curriculumSection: StudentMistakeCurriculumSectionKey? = null,
    val knowledgeNode: KnowledgeNodeRef? = null,
    val favoriteOnly: Boolean = false,
) {
    init {
        subject?.requireHighSchoolSubject()
        require(text == null || text.normalizedLibrarySearchText().isNotEmpty()) {
            "Mistake-library search text is invalid"
        }
        require(text == null || text.length <= LIBRARY_MAX_SEARCH_CHARS) {
            "Mistake-library search text exceeds the supported size"
        }
        require(subject == null || knowledgeNode == null || subject == knowledgeNode.subject) {
            "Mistake-library subject and knowledge filter must agree"
        }
        text?.let(StudentMistakeSearchNormalizer::query)
    }
}

data class StudentMistakeLibraryPageRequest(
    val filter: StudentMistakeLibraryFilter = StudentMistakeLibraryFilter(),
    val cursor: StudentMistakeLibraryCursor? = null,
    val limit: Int = DEFAULT_LIBRARY_PAGE_SIZE,
) {
    init {
        require(limit in 1..MAX_LIBRARY_PAGE_SIZE) {
            "Mistake-library page size is outside the supported range"
        }
    }
}

data class StudentMistakeLibraryCollectionSummary(
    val state: StudentMistakeEntryState,
    val favorite: Boolean,
    val addedAtEpochMillis: Long?,
    val changedAtEpochMillis: Long,
) {
    init {
        require(state != StudentMistakeEntryState.NONE) {
            "Mistake-library collection state must represent a saved entry"
        }
        require(addedAtEpochMillis == null || addedAtEpochMillis >= 0) {
            "Mistake-library add time must not be negative"
        }
        require(changedAtEpochMillis >= 0) {
            "Mistake-library change time must not be negative"
        }
    }
}

data class StudentMistakeLibrarySection(
    val key: StudentMistakeCurriculumSectionKey,
)

data class StudentMistakeLibraryItem(
    val entryId: StudentMistakeEntryId,
    val problemRevision: StudentProblemRevisionRef,
    val subject: SubjectKind,
    val title: String?,
    val stemPreview: String,
    val practiceUnitTitle: String,
    val estimatedDurationSeconds: Int,
    val collection: StudentMistakeLibraryCollectionSummary,
    val sections: List<StudentMistakeLibrarySection>,
    val knowledgeNodes: List<KnowledgeNodeRef>,
) {
    init {
        require(problemRevision.problem.subject == subject) {
            "Mistake-library item subject must match its exact problem revision"
        }
    }
}

sealed interface StudentMistakeLibraryPageResult {
    data class Content(
        val items: List<StudentMistakeLibraryItem>,
        val nextCursor: StudentMistakeLibraryCursor?,
    ) : StudentMistakeLibraryPageResult

    data object ReloadRequired : StudentMistakeLibraryPageResult

    data class Preparing(
        val indexedDocumentCount: Long,
    ) : StudentMistakeLibraryPageResult
}

sealed interface StudentMistakeSearchIndexStatus {
    data class Preparing(
        val indexedDocumentCount: Long,
    ) : StudentMistakeSearchIndexStatus

    data object Ready : StudentMistakeSearchIndexStatus
}

data class StudentMistakeLibraryImage(
    val localContentUri: String,
    val mediaType: String,
    val ordinal: Int,
    val widthPixels: Int?,
    val heightPixels: Int?,
)

data class StudentMistakeLibraryDetail(
    val entryId: StudentMistakeEntryId,
    val problemRevision: StudentProblemRevisionRef,
    val subject: SubjectKind,
    val title: String?,
    val stemMarkdown: String,
    val practiceUnitKind: StudentPracticeUnitKind,
    val practiceUnitTitle: String,
    val estimatedDurationSeconds: Int,
    val images: List<StudentMistakeLibraryImage>,
    val collection: StudentMistakeLibraryCollectionSummary,
    val sections: List<StudentMistakeLibrarySection>,
    val knowledgeNodes: List<KnowledgeNodeRef>,
) {
    init {
        require(problemRevision.problem.subject == subject) {
            "Mistake-library detail subject must match its exact problem revision"
        }
    }
}

data class StudentMistakeSubjectFacet(
    val subject: SubjectKind,
    val problemCount: Long,
)

data class StudentMistakeSectionFacet(
    val subject: SubjectKind,
    val section: StudentMistakeLibrarySection,
    val problemCount: Long,
)

data class StudentMistakeKnowledgeFacet(
    val knowledgeNode: KnowledgeNodeRef,
    val problemCount: Long,
)

data class StudentMistakeLibraryFacets(
    val changeVersion: Long,
    val subjects: List<StudentMistakeSubjectFacet>,
    val sections: List<StudentMistakeSectionFacet>,
    val knowledgeNodes: List<StudentMistakeKnowledgeFacet>,
)

/**
 * Learner-scoped read model for the mistake library.
 *
 * The learner identity is captured by the owner runtime and never accepted from a caller. This
 * surface contains only mistake-book presentation data and stable knowledge references.
 */
interface LearnerBoundStudentMistakeLibraryPort {
    fun observeChangeVersion(): Flow<Long>

    suspend fun readPage(
        request: StudentMistakeLibraryPageRequest = StudentMistakeLibraryPageRequest(),
    ): StudentMistakeLibraryPageResult

    suspend fun readDetail(entryId: StudentMistakeEntryId): StudentMistakeLibraryDetail?

    suspend fun readFacets(): StudentMistakeLibraryFacets

    suspend fun prepareSearchIndex(
        maxDocuments: Int = DEFAULT_SEARCH_INDEX_BATCH_SIZE,
    ): StudentMistakeSearchIndexStatus
}

internal class RoomLearnerBoundStudentMistakeLibraryPort(
    private val dao: StudentMistakeDao,
    private val libraryDao: StudentMistakeLibraryDao,
    private val learnerId: String,
) : LearnerBoundStudentMistakeLibraryPort {
    private val learnerCanonicalFingerprint = learnerId.libraryLearnerFingerprint()

    init {
        learnerId.requireStoreText("Mistake-library learner id", MAX_ID_CHARS)
    }

    override fun observeChangeVersion(): Flow<Long> = dao.observeChangeVersion(learnerId)

    override suspend fun readPage(
        request: StudentMistakeLibraryPageRequest,
    ): StudentMistakeLibraryPageResult {
        val queryFingerprint = request.filter.canonicalFingerprint()
        val cursor = request.cursor
        if (
            cursor != null &&
            (
                cursor.learnerCanonicalFingerprint != learnerCanonicalFingerprint ||
                    cursor.queryCanonicalFingerprint != queryFingerprint
            )
        ) {
            return StudentMistakeLibraryPageResult.ReloadRequired
        }
        val normalizedSearch =
            request.filter.text?.let(StudentMistakeSearchNormalizer::query)
        if (normalizedSearch != null) {
            when (val status = prepareSearchIndex()) {
                is StudentMistakeSearchIndexStatus.Preparing ->
                    return StudentMistakeLibraryPageResult.Preparing(
                        indexedDocumentCount = status.indexedDocumentCount,
                    )

                StudentMistakeSearchIndexStatus.Ready -> Unit
            }
        }
        val knowledgeNode = request.filter.knowledgeNode
        return when (
            val snapshot =
                libraryDao.readLibraryPageSnapshot(
                    learnerId = learnerId,
                    expectedChangeVersion = cursor?.changeVersion,
                    subject = request.filter.subject?.name,
                    favoriteOnly = request.filter.favoriteOnly,
                    ftsMatchExpression = normalizedSearch?.ftsMatchExpression,
                    normalizedSearchText = normalizedSearch?.normalizedText,
                    curriculumSectionLabelId = request.filter.curriculumSection?.value,
                    knowledgeSubject = knowledgeNode?.subject?.name,
                    knowledgeNodeId = knowledgeNode?.knowledgeNodeId,
                    knowledgeTaxonomyVersion = knowledgeNode?.taxonomyVersion,
                    knowledgePackVersion = knowledgeNode?.knowledgePackVersion,
                    cursorChangedAtEpochMillis = cursor?.changedAtEpochMillis,
                    cursorProblemId = cursor?.problemId,
                    pageSize = request.limit,
                )
        ) {
            StudentMistakeLibraryPageSnapshot.ReloadRequired ->
                StudentMistakeLibraryPageResult.ReloadRequired

            is StudentMistakeLibraryPageSnapshot.Ready ->
                snapshot.toPageResult(
                    learnerId = learnerId,
                    learnerCanonicalFingerprint = learnerCanonicalFingerprint,
                    queryFingerprint = queryFingerprint,
                    pageSize = request.limit,
                )
        }
    }

    override suspend fun readDetail(
        entryId: StudentMistakeEntryId,
    ): StudentMistakeLibraryDetail? =
        libraryDao.readLibraryDetailSnapshot(learnerId, entryId.value)?.toDetail(learnerId)

    override suspend fun readFacets(): StudentMistakeLibraryFacets =
        libraryDao.readLibraryFacetSnapshot(learnerId).toFacets()

    override suspend fun prepareSearchIndex(
        maxDocuments: Int,
    ): StudentMistakeSearchIndexStatus {
        require(maxDocuments in 1..MAX_SEARCH_INDEX_BATCH_SIZE) {
            "Search-index batch size is outside the supported range"
        }
        val nowEpochMillis = System.currentTimeMillis()
        val state =
            dao.ensureSearchIndexState(
                StudentProblemSearchIndexStateEntity(
                    indexKey = STUDENT_PROBLEM_SEARCH_INDEX_KEY,
                    state = StudentProblemSearchIndexState.PREPARING.name,
                    afterRevisionId = null,
                    indexedDocumentCount = 0,
                    updatedAtEpochMillis = nowEpochMillis,
                ),
            )
        if (state.state == StudentProblemSearchIndexState.READY.name) {
            return StudentMistakeSearchIndexStatus.Ready
        }
        check(state.state == StudentProblemSearchIndexState.PREPARING.name) {
            "Corrupt mistake-library search-index state"
        }
        val rows =
            dao.readSearchIndexBackfillPage(
                afterRevisionId = state.afterRevisionId,
                limit = maxDocuments + 1,
            )
        val batch = rows.take(maxDocuments)
        val documents =
            batch.map { row ->
                val normalized =
                    StudentMistakeSearchNormalizer.document(
                        revisionId = row.revisionId,
                        title = row.title,
                        stemMarkdown = row.stemMarkdown,
                        practiceUnitTitle = row.practiceUnitTitle,
                    )
                StudentProblemSearchDocumentEntity(
                    revisionId = row.revisionId,
                    sourceCanonicalFingerprint = normalized.sourceCanonicalFingerprint,
                    normalizedText = normalized.normalizedText,
                    tokenizedText = normalized.tokenizedText,
                    indexedAtEpochMillis = nowEpochMillis,
                )
            }
        dao.applySearchIndexBackfillBatch(
            expectedAfterRevisionId = state.afterRevisionId,
            documents = documents,
            nextAfterRevisionId = batch.lastOrNull()?.revisionId ?: state.afterRevisionId,
            completed = rows.size <= maxDocuments,
            indexedDocumentCount = state.indexedDocumentCount + documents.size,
            updatedAtEpochMillis = nowEpochMillis,
        )
        val current =
            checkNotNull(dao.readSearchIndexState()) {
                "Search-index state disappeared after a backfill batch"
            }
        return when (current.state) {
            StudentProblemSearchIndexState.READY.name ->
                StudentMistakeSearchIndexStatus.Ready

            StudentProblemSearchIndexState.PREPARING.name ->
                StudentMistakeSearchIndexStatus.Preparing(current.indexedDocumentCount)

            else -> error("Corrupt mistake-library search-index state")
        }
    }
}

private fun StudentMistakeLibraryPageSnapshot.Ready.toPageResult(
    learnerId: String,
    learnerCanonicalFingerprint: String,
    queryFingerprint: String,
    pageSize: Int,
): StudentMistakeLibraryPageResult.Content {
    val pageRows = rows.take(pageSize)
    val classificationsByRevision =
        classifications.groupBy(StudentMistakeLibraryClassificationRow::basisRevisionId)
    val nextCursor =
        if (rows.size > pageSize) {
            pageRows.lastOrNull()?.let { row ->
                StudentMistakeLibraryCursor.create(
                    changeVersion,
                    learnerCanonicalFingerprint,
                    queryFingerprint,
                    row.changedAtEpochMillis,
                    row.problemId,
                )
            }
        } else {
            null
        }
    return StudentMistakeLibraryPageResult.Content(
        items =
            pageRows.map { row ->
                row.toLibraryItem(
                    learnerId = learnerId,
                    classifications = classificationsByRevision[row.revisionId].orEmpty(),
                )
            },
        nextCursor = nextCursor,
    )
}

private fun StudentMistakeLibraryListRow.toLibraryItem(
    learnerId: String,
    classifications: List<StudentMistakeLibraryClassificationRow>,
): StudentMistakeLibraryItem {
    val resolvedSubject =
        enumValueOrCorrupt<SubjectKind>(
            subject,
            "mistake-library subject",
        )
    val problem =
        StudentProblemRef(
            learnerId = learnerId,
            subject = resolvedSubject,
            problemId = problemId,
            practiceUnitId = practiceUnitId,
        )
    return StudentMistakeLibraryItem(
        entryId = StudentMistakeEntryId(checkNotNull(errorBookEntryId)),
        problemRevision =
            StudentProblemRevisionRef(
                problem = problem,
                revisionId = revisionId,
                revisionNumber = revisionNumber,
                documentCanonicalFingerprint = documentCanonicalFingerprint,
            ),
        subject = resolvedSubject,
        title = title,
        stemPreview = stemPreview,
        practiceUnitTitle = practiceUnitTitle,
        estimatedDurationSeconds = estimatedDurationSeconds,
        collection =
            StudentMistakeLibraryCollectionSummary(
                state = enumValueOrCorrupt(mistakeState, "mistake-library collection state"),
                favorite = favorite,
                addedAtEpochMillis = addedAtEpochMillis,
                changedAtEpochMillis = changedAtEpochMillis,
            ),
        sections = classifications.toSections(),
        knowledgeNodes = classifications.toKnowledgeNodes(),
    )
}

private fun StudentMistakeLibraryDetailSnapshot.toDetail(
    learnerId: String,
): StudentMistakeLibraryDetail =
    StudentMistakeLibraryDetail(
        entryId = StudentMistakeEntryId(checkNotNull(row.errorBookEntryId)),
        problemRevision =
            StudentProblemRevisionRef(
                problem =
                    StudentProblemRef(
                        learnerId = learnerId,
                        subject = enumValueOrCorrupt(row.subject, "mistake-library subject"),
                        problemId = row.problemId,
                        practiceUnitId = row.practiceUnitId,
                    ),
                revisionId = row.revisionId,
                revisionNumber = row.revisionNumber,
                documentCanonicalFingerprint = row.documentCanonicalFingerprint,
            ),
        subject = enumValueOrCorrupt(row.subject, "mistake-library subject"),
        title = row.title,
        stemMarkdown = row.stemMarkdown,
        practiceUnitKind =
            enumValueOrCorrupt(row.practiceUnitKind, "mistake-library practice-unit kind"),
        practiceUnitTitle = row.practiceUnitTitle,
        estimatedDurationSeconds = row.estimatedDurationSeconds,
        images =
            images.map { image ->
                StudentMistakeLibraryImage(
                    localContentUri = image.localContentUri,
                    mediaType = image.mediaType,
                    ordinal = image.ordinal,
                    widthPixels = image.widthPixels,
                    heightPixels = image.heightPixels,
                )
            },
        collection =
            StudentMistakeLibraryCollectionSummary(
                state =
                    enumValueOrCorrupt(
                        row.mistakeState,
                        "mistake-library collection state",
                    ),
                favorite = row.favorite,
                addedAtEpochMillis = row.addedAtEpochMillis,
                changedAtEpochMillis = row.changedAtEpochMillis,
            ),
        sections = classifications.toSections(),
        knowledgeNodes = classifications.toKnowledgeNodes(),
    )

private fun List<StudentMistakeLibraryClassificationRow>.toSections():
    List<StudentMistakeLibrarySection> =
    asSequence()
        .filter { it.dimension == StudentProblemClassificationDimension.CURRICULUM_SECTION.name }
        .map { row ->
            val knowledgeColumns =
                listOf(
                    row.knowledgeSubject,
                    row.knowledgeNodeId,
                    row.knowledgeTaxonomyVersion,
                    row.knowledgePackVersion,
                )
            check(
                knowledgeColumns.all { it == null } ||
                    knowledgeColumns.all { it != null },
            ) {
                "Corrupt mistake-library section classification"
            }
            StudentMistakeLibrarySection(
                key = StudentMistakeCurriculumSectionKey(row.labelId),
            )
        }
        .distinctBy { it.key }
        .sortedBy { it.key.value }
        .toList()

private fun List<StudentMistakeLibraryClassificationRow>.toKnowledgeNodes():
    List<KnowledgeNodeRef> =
    asSequence()
        .filter { it.dimension == StudentProblemClassificationDimension.KNOWLEDGE.name }
        .map { row ->
            KnowledgeNodeRef(
                subject =
                    enumValueOrCorrupt(
                        checkNotNull(row.knowledgeSubject),
                        "mistake-library knowledge subject",
                    ),
                knowledgeNodeId = checkNotNull(row.knowledgeNodeId),
                taxonomyVersion = checkNotNull(row.knowledgeTaxonomyVersion),
                knowledgePackVersion = checkNotNull(row.knowledgePackVersion),
            )
        }
        .distinctBy(KnowledgeNodeRef::canonicalFingerprint)
        .sortedBy(KnowledgeNodeRef::canonicalFingerprint)
        .toList()

private fun StudentMistakeLibraryFacetSnapshot.toFacets(): StudentMistakeLibraryFacets =
    StudentMistakeLibraryFacets(
        changeVersion = changeVersion,
        subjects =
            subjects.map { row ->
                StudentMistakeSubjectFacet(
                    subject = enumValueOrCorrupt(row.subject, "mistake-library facet subject"),
                    problemCount = row.problemCount.requirePositiveFacetCount(),
                )
            },
        sections =
            sections.map { row ->
                StudentMistakeSectionFacet(
                    subject =
                        enumValueOrCorrupt(
                            row.subject,
                            "mistake-library section-facet subject",
                        ),
                    section =
                        StudentMistakeLibrarySection(
                            key = StudentMistakeCurriculumSectionKey(row.labelId),
                        ),
                    problemCount = row.problemCount.requirePositiveFacetCount(),
                )
            },
        knowledgeNodes =
            knowledge.map { row ->
                StudentMistakeKnowledgeFacet(
                    knowledgeNode =
                        KnowledgeNodeRef(
                            subject =
                                enumValueOrCorrupt(
                                    checkNotNull(row.knowledgeSubject),
                                    "mistake-library knowledge-facet subject",
                                ),
                            knowledgeNodeId = checkNotNull(row.knowledgeNodeId),
                            taxonomyVersion = checkNotNull(row.knowledgeTaxonomyVersion),
                            knowledgePackVersion = checkNotNull(row.knowledgePackVersion),
                        ),
                    problemCount = row.problemCount.requirePositiveFacetCount(),
                )
            },
    )

private fun Long.requirePositiveFacetCount(): Long =
    also { count ->
        check(count > 0) { "Corrupt mistake-library facet count" }
    }

private fun StudentMistakeLibraryFilter.canonicalFingerprint(): String =
    CanonicalSha256(LIBRARY_QUERY_FINGERPRINT_DOMAIN)
        .nullableField("subject", subject?.name)
        .nullableField("text", text?.normalizedLibrarySearchText())
        .nullableField("curriculumSection", curriculumSection?.value)
        .nullableField("knowledgeNode", knowledgeNode?.canonicalFingerprint)
        .field("favoriteOnly", favoriteOnly)
        .finish()

private fun String.libraryLearnerFingerprint(): String =
    CanonicalSha256(LIBRARY_LEARNER_FINGERPRINT_DOMAIN)
        .field("learner", this)
        .finish()

private fun String.normalizedLibrarySearchText(): String =
    StudentMistakeSearchNormalizer.normalize(this)

const val DEFAULT_LIBRARY_PAGE_SIZE = 30
const val MAX_LIBRARY_PAGE_SIZE = 64
const val DEFAULT_SEARCH_INDEX_BATCH_SIZE = 64
const val MAX_SEARCH_INDEX_BATCH_SIZE = 512
private const val LIBRARY_MAX_SEARCH_CHARS = 256
private const val LIBRARY_QUERY_FINGERPRINT_DOMAIN = "student-mistake-library-query-v1"
private const val LIBRARY_LEARNER_FINGERPRINT_DOMAIN = "student-mistake-library-learner-v1"
