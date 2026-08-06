package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.domain.MistakeDetail
import com.tingyun.smartmistakebook.core.domain.MistakeDetailIdentity
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionSummary
import com.tingyun.smartmistakebook.core.domain.MistakeSourceAsset
import com.tingyun.smartmistakebook.core.domain.MistakeSourceLocation
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeDetailQuery
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeEntryId
import com.tingyun.smartmistakebook.core.student.mistake.database.LearnerBoundStudentMistakeLibraryPort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeStore
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemClassificationResult
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemDocument
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemImageReference
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemRevisionHistoryCursor
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemRevisionHistoryItem
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemRevisionHistoryPage
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemRevisionHistoryQuery
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal interface StudentMistakeDetailReadPort {
    suspend fun readDetail(
        query: StudentMistakeDetailQuery,
    ): StudentProblemDocument?

    suspend fun readCurrentClassifications(
        learnerId: String,
        revision: StudentProblemRevisionRef,
    ): List<StudentProblemClassificationResult>

    suspend fun readRevisionHistory(
        query: StudentProblemRevisionHistoryQuery,
    ): StudentProblemRevisionHistoryPage
}

internal class StudentMistakeStoreDetailReadPort(
    private val store: StudentMistakeStore,
) : StudentMistakeDetailReadPort {
    override suspend fun readDetail(
        query: StudentMistakeDetailQuery,
    ): StudentProblemDocument? = store.readMistakeDetail(query)

    override suspend fun readCurrentClassifications(
        learnerId: String,
        revision: StudentProblemRevisionRef,
    ): List<StudentProblemClassificationResult> {
        if (revision.problem.learnerId != learnerId) return emptyList()
        return store.readCurrentClassifications(revision)
    }

    override suspend fun readRevisionHistory(
        query: StudentProblemRevisionHistoryQuery,
    ): StudentProblemRevisionHistoryPage = store.readRevisionHistory(query)
}

internal data class StudentMistakeRevisionDetailQuery(
    val learnerId: String,
    val errorBookEntryId: String,
    val revision: StudentProblemRevisionRef,
) {
    init {
        require(revision.problem.learnerId == learnerId) {
            "Detail revision must belong to the authenticated learner"
        }
        currentDetailQuery()
    }

    fun currentDetailQuery(): StudentMistakeDetailQuery =
        StudentMistakeDetailQuery(
            learnerId = learnerId,
            problem = revision.problem,
            errorBookEntryId = errorBookEntryId,
        )
}

private data class StudentMistakeDetailProjection(
    val document: StudentProblemDocument,
    val classifications: List<StudentProblemClassificationResult>,
    val imageCommittedAtEpochMillis: Long?,
)

internal class StudentMistakeStoreMistakeDetailRepository(
    private val readPort: StudentMistakeDetailReadPort,
    private val exactDetailLibrary: LearnerBoundStudentMistakeLibraryPort? = null,
    private val expectedLearnerId: String? = null,
) : MistakeDetailRepository {
    init {
        require((exactDetailLibrary == null) == (expectedLearnerId == null)) {
            "Exact detail lookup requires both a learner-bound library and expected learner"
        }
        expectedLearnerId?.let { require(it.isNotBlank()) { "Expected learner id must not be blank" } }
    }

    // The legacy domain methods carry only an entry id or partial string key. They cannot prove
    // the authenticated learner plus the typed problem/revision identity, so they stay closed.
    override fun observe(errorBookEntryId: String): Flow<MistakeDetailState> {
        require(errorBookEntryId.isNotBlank()) { "errorBookEntryId must not be blank" }
        return failClosedDetailFlow()
    }

    override fun observeExact(key: MistakeRevisionKey): Flow<MistakeDetailState> =
        failClosedDetailFlow()

    override suspend fun readExact(key: MistakeRevisionKey): MistakeDetailState =
        MistakeDetailState.NotFound

    override suspend fun readExact(
        keys: List<MistakeRevisionKey>,
    ): List<MistakeDetailState> {
        require(keys.size <= MAX_BATCH_SIZE) { "Mistake-detail batch is too large" }
        return List(keys.size) { MistakeDetailState.NotFound }
    }

    override fun observeRevisionHistory(
        errorBookEntryId: String,
    ): Flow<List<MistakeRevisionSummary>> {
        require(errorBookEntryId.isNotBlank()) { "errorBookEntryId must not be blank" }
        return flow { emit(emptyList()) }
    }

    internal fun observeScoped(
        query: StudentMistakeDetailQuery,
    ): Flow<MistakeDetailState> = flow {
        emit(MistakeDetailState.Loading)
        emit(readScoped(query))
    }.flowOn(Dispatchers.IO)

    internal suspend fun readScoped(
        query: StudentMistakeDetailQuery,
    ): MistakeDetailState =
        withContext(Dispatchers.IO) {
            readProjection(query)?.toState() ?: MistakeDetailState.NotFound
        }

    /**
     * Reads exact current identity from the learner-bound student authority. It does not consult
     * catalog state or wait for the discardable projection to scan any entries.
     */
    internal suspend fun readLearnerBound(
        entryId: StudentMistakeEntryId,
    ): MistakeDetailState = withContext(Dispatchers.IO) {
        val exactQuery = resolveLearnerBoundRevision(entryId)
            ?: return@withContext MistakeDetailState.NotFound
        readExactScopedState(exactQuery)
    }

    internal fun observeLearnerBound(
        entryId: StudentMistakeEntryId,
    ): Flow<MistakeDetailState> = flow {
        emit(MistakeDetailState.Loading)
        emit(readLearnerBound(entryId))
    }.flowOn(Dispatchers.IO)

    internal suspend fun readLearnerBoundExactBatch(
        keys: List<MistakeRevisionKey>,
    ): List<MistakeDetailState> = withContext(Dispatchers.IO) {
        require(keys.size <= MAX_BATCH_SIZE) { "Mistake-detail batch is too large" }
        val currentByEntryId =
            keys.asSequence()
                .map(MistakeRevisionKey::entryId)
                .distinct()
                .associateWith { entryId ->
                    readLearnerBound(StudentMistakeEntryId(entryId))
                }
        keys.map { key ->
            currentByEntryId.getValue(key.entryId).requireExact(key)
        }
    }

    internal fun observeLearnerBoundRevisionHistory(
        entryId: StudentMistakeEntryId,
    ): Flow<List<MistakeRevisionSummary>> = flow {
        val exactQuery = resolveLearnerBoundRevision(entryId)
        if (exactQuery == null) {
            emit(emptyList())
        } else {
            emitAll(observeRevisionHistoryScoped(exactQuery.currentDetailQuery()))
        }
    }.flowOn(Dispatchers.IO)

    internal suspend fun readScopedBatch(
        queries: List<StudentMistakeDetailQuery>,
    ): List<MistakeDetailState> = withContext(Dispatchers.IO) {
        require(queries.size <= MAX_BATCH_SIZE) { "Mistake-detail batch is too large" }
        queries
            .distinct()
            .associateWith { query -> readProjection(query) }
            .let { projectionsByQuery ->
                queries.map { query ->
                    projectionsByQuery[query]?.toState() ?: MistakeDetailState.NotFound
                }
            }
    }

    internal fun observeExactScoped(
        query: StudentMistakeRevisionDetailQuery,
    ): Flow<MistakeDetailState> = flow {
        emit(MistakeDetailState.Loading)
        emit(readExactScopedState(query))
    }.flowOn(Dispatchers.IO)

    internal suspend fun readExactScoped(
        query: StudentMistakeRevisionDetailQuery,
    ): MistakeDetailState =
        withContext(Dispatchers.IO) { readExactScopedState(query) }

    internal suspend fun readExactScopedBatch(
        queries: List<StudentMistakeRevisionDetailQuery>,
    ): List<MistakeDetailState> = withContext(Dispatchers.IO) {
        require(queries.size <= MAX_BATCH_SIZE) { "Mistake-detail batch is too large" }
        val projectionsByDetail =
            queries
                .map(StudentMistakeRevisionDetailQuery::currentDetailQuery)
                .distinct()
                .associateWith { query -> readProjection(query) }
        queries.map { query ->
            projectionsByDetail[query.currentDetailQuery()]
                ?.takeIf { projection -> projection.matches(query) }
                ?.toState()
                ?: MistakeDetailState.NotFound
        }
    }

    internal fun observeRevisionHistoryScoped(
        query: StudentMistakeDetailQuery,
    ): Flow<List<MistakeRevisionSummary>> {
        return flow {
            val current = readPort.readDetail(query)
            if (current == null || !current.matches(query)) {
                emit(emptyList())
                return@flow
            }
            val history = readAllRevisionHistory(query)
            if (!history.isValidFor(query.problem, current.revision)) {
                emit(emptyList())
                return@flow
            }
            val summaries =
                history.mapNotNull { item ->
                    item.toSummaryOrNull(
                        errorBookEntryId = query.errorBookEntryId,
                        currentRevisionId = current.revision.revisionId,
                    )
                }
            emit(if (summaries.size == history.size) summaries else emptyList())
        }.flowOn(Dispatchers.IO)
    }

    private fun failClosedDetailFlow(): Flow<MistakeDetailState> = flow {
        emit(MistakeDetailState.Loading)
        emit(MistakeDetailState.NotFound)
    }

    private suspend fun readExactScopedState(
        query: StudentMistakeRevisionDetailQuery,
    ): MistakeDetailState =
        readProjection(query.currentDetailQuery())
            ?.takeIf { projection -> projection.matches(query) }
            ?.toState()
            ?: MistakeDetailState.NotFound

    private suspend fun resolveLearnerBoundRevision(
        entryId: StudentMistakeEntryId,
    ): StudentMistakeRevisionDetailQuery? {
        val library = exactDetailLibrary ?: return null
        val learnerId = expectedLearnerId ?: return null
        val exact = library.readDetail(entryId) ?: return null
        if (
            exact.entryId != entryId ||
            exact.problemRevision.problem.learnerId != learnerId ||
            exact.problemRevision.problem.subject != exact.subject
        ) {
            return null
        }
        return StudentMistakeRevisionDetailQuery(
            learnerId = learnerId,
            errorBookEntryId = entryId.value,
            revision = exact.problemRevision,
        )
    }

    private suspend fun readProjection(
        query: StudentMistakeDetailQuery,
    ): StudentMistakeDetailProjection? {
        val document = readPort.readDetail(query) ?: return null
        if (!document.matches(query)) return null
        val classifications =
            readPort.readCurrentClassifications(
                learnerId = query.learnerId,
                revision = document.revision,
            )
        val imageCommittedAtEpochMillis =
            if (document.originalImages.isEmpty()) {
                null
            } else {
                readRevisionCommittedAt(
                    query = query,
                    revision = document.revision,
                )
            }
        return StudentMistakeDetailProjection(
            document = document,
            classifications = classifications,
            imageCommittedAtEpochMillis = imageCommittedAtEpochMillis,
        )
    }

    private suspend fun readAllRevisionHistory(
        query: StudentMistakeDetailQuery,
    ): List<StudentProblemRevisionHistoryItem> {
        val items = mutableListOf<StudentProblemRevisionHistoryItem>()
        val visitedCursors = hashSetOf<StudentProblemRevisionHistoryCursor>()
        var cursor: StudentProblemRevisionHistoryCursor? = null
        do {
            val page =
                readPort.readRevisionHistory(
                    StudentProblemRevisionHistoryQuery(
                        learnerId = query.learnerId,
                        problem = query.problem,
                        errorBookEntryId = query.errorBookEntryId,
                        cursor = cursor,
                        limit = HISTORY_PAGE_SIZE,
                    ),
                )
            check(page.items.size <= HISTORY_PAGE_SIZE) {
                "Student mistake history page exceeded its requested limit"
            }
            items += page.items
            cursor = page.checkedNextCursor(visitedCursors)
        } while (cursor != null)
        return items
    }

    private suspend fun readRevisionCommittedAt(
        query: StudentMistakeDetailQuery,
        revision: StudentProblemRevisionRef,
    ): Long? {
        val visitedCursors = hashSetOf<StudentProblemRevisionHistoryCursor>()
        var cursor: StudentProblemRevisionHistoryCursor? = null
        do {
            val page =
                readPort.readRevisionHistory(
                    StudentProblemRevisionHistoryQuery(
                        learnerId = query.learnerId,
                        problem = query.problem,
                        errorBookEntryId = query.errorBookEntryId,
                        cursor = cursor,
                        limit = HISTORY_PAGE_SIZE,
                    ),
                )
            check(page.items.size <= HISTORY_PAGE_SIZE) {
                "Student mistake history page exceeded its requested limit"
            }
            val matches = page.items.filter { item -> item.revision == revision }
            if (matches.size > 1) return null
            matches.singleOrNull()?.let { item ->
                // Original images are committed as part of this exact immutable revision.
                return item.committedAtEpochMillis
            }
            cursor = page.checkedNextCursor(visitedCursors)
        } while (cursor != null)
        return null
    }

    private fun StudentProblemRevisionHistoryPage.checkedNextCursor(
        visitedCursors: MutableSet<StudentProblemRevisionHistoryCursor>,
    ): StudentProblemRevisionHistoryCursor? {
        val next = nextCursor
        check(next == null || visitedCursors.add(next)) {
            "Student mistake history returned a repeated cursor"
        }
        check(next == null || items.isNotEmpty()) {
            "Student mistake history returned an empty continuation page"
        }
        return next
    }

    private fun StudentProblemDocument.matches(
        query: StudentMistakeDetailQuery,
    ): Boolean =
        errorBookEntryId == query.errorBookEntryId &&
            revision.problem == query.problem &&
            revision.problem.learnerId == query.learnerId

    private fun StudentMistakeDetailProjection.matches(
        query: StudentMistakeRevisionDetailQuery,
    ): Boolean =
        document.errorBookEntryId == query.errorBookEntryId &&
            document.revision == query.revision &&
            document.revision.problem.learnerId == query.learnerId

    private fun StudentMistakeDetailProjection.toState(): MistakeDetailState {
        val identity = document.toIdentityOrNull() ?: return MistakeDetailState.NotFound
        if (!classifications.areScopedTo(document.revision)) {
            return MistakeDetailState.CorruptSnapshot(identity)
        }
        // Classifications fence the exact revision but have no slot in MistakeDetail. Never smuggle
        // their labels or public-knowledge references into the title or Markdown.
        val detail =
            MistakeDetail(
                identity = identity,
                fallbackMarkdown = document.stemMarkdown,
                source = document.originalImages.toSourceSet(imageCommittedAtEpochMillis),
                tutorConversation = null,
            )
        val capturedDocument =
            document.capturedQuestionDocument
                ?: return MistakeDetailState.Legacy(detail)
        val fingerprintMatches =
            CapturedQuestionDocumentFingerprint.of(capturedDocument) ==
                document.revision.documentCanonicalFingerprint
        if (!fingerprintMatches ||
            CapturedQuestionDocumentValidator.validateForCommit(capturedDocument).isNotEmpty()
        ) {
            return MistakeDetailState.CorruptSnapshot(identity)
        }
        return MistakeDetailState.Ready(
            detail = detail,
            questionDocument = capturedDocument,
        )
    }

    private fun StudentProblemDocument.toIdentityOrNull(): MistakeDetailIdentity? {
        val entryId = errorBookEntryId ?: return null
        val resolvedTitle =
            title?.trim()?.takeIf(String::isNotEmpty)
                ?: practiceUnitTitle.trim().takeIf(String::isNotEmpty)
                ?: return null
        return MistakeDetailIdentity(
            errorBookEntryId = entryId,
            problemId = revision.problem.problemId,
            problemRevisionId = revision.revisionId,
            revisionNumber = revision.revisionNumber,
            title = resolvedTitle,
            subject = revision.problem.subject.name,
            practiceUnitId = revision.problem.practiceUnitId,
        )
    }

    private fun List<StudentProblemClassificationResult>.areScopedTo(
        revision: StudentProblemRevisionRef,
    ): Boolean =
        all { classification -> classification.problemRevision == revision } &&
            map(StudentProblemClassificationResult::classificationId).distinct().size == size

    private fun List<StudentProblemImageReference>.toSourceSet(
        committedAtEpochMillis: Long?,
    ): MistakeSourceSet {
        if (isEmpty()) return MistakeSourceSet.Missing
        if (committedAtEpochMillis == null ||
            map(StudentProblemImageReference::ordinal) != indices.toList() ||
            any { image ->
                image.widthPixels == null ||
                    image.heightPixels == null ||
                    image.byteSize == null
            }
        ) {
            return MistakeSourceSet.Missing
        }
        return MistakeSourceSet.Present(
            map { image ->
                MistakeSourceAsset(
                    role = QUESTION_SOURCE_ROLE,
                    sourceAssetId = image.imageReferenceId,
                    contentSha256 = image.contentCanonicalFingerprint,
                    mimeType = image.mediaType,
                    byteSize = checkNotNull(image.byteSize),
                    width = checkNotNull(image.widthPixels),
                    height = checkNotNull(image.heightPixels),
                    sourceType = STUDENT_MISTAKE_ORIGINAL_SOURCE_TYPE,
                    createdAtEpochMillis = committedAtEpochMillis,
                    location = image.localContentUri.toLocalSourceLocation(),
                )
            },
        )
    }

    private fun String.toLocalSourceLocation(): MistakeSourceLocation {
        val scheme =
            runCatching { URI(this).scheme?.lowercase() }
                .getOrNull()
        return if (scheme in LOCAL_SOURCE_SCHEMES) {
            MistakeSourceLocation.Available(this)
        } else {
            MistakeSourceLocation.Unavailable
        }
    }

    private fun List<StudentProblemRevisionHistoryItem>.isValidFor(
        expectedProblem: StudentProblemRef,
        currentRevision: StudentProblemRevisionRef,
    ): Boolean =
        isNotEmpty() &&
            count { item -> item.revision == currentRevision } == 1 &&
            currentRevision.problem == expectedProblem &&
            all { item -> item.revision.problem == expectedProblem } &&
            map { item -> item.revision.revisionId }.distinct().size == size

    private fun StudentProblemRevisionHistoryItem.toSummaryOrNull(
        errorBookEntryId: String,
        currentRevisionId: String,
    ): MistakeRevisionSummary? {
        val resolvedTitle =
            title?.trim()?.takeIf(String::isNotEmpty)
                ?: stemPreview.trim().takeIf(String::isNotEmpty)
                ?: return null
        return MistakeRevisionSummary(
            entryId = errorBookEntryId,
            problemId = revision.problem.problemId,
            problemRevisionId = revision.revisionId,
            revisionNumber = revision.revisionNumber,
            title = resolvedTitle,
            createdAtEpochMillis = committedAtEpochMillis,
            isCurrent = revision.revisionId == currentRevisionId,
        )
    }

    private companion object {
        const val MAX_BATCH_SIZE = 100
        const val HISTORY_PAGE_SIZE = 100
        const val QUESTION_SOURCE_ROLE = "QUESTION_SOURCE"
        const val STUDENT_MISTAKE_ORIGINAL_SOURCE_TYPE = "STUDENT_MISTAKE_ORIGINAL"

        val LOCAL_SOURCE_SCHEMES = setOf("content", "file")
    }
}

/**
 * First read-cutover slice for the legacy feature contract.
 *
 * Current content and revision history are read from student-mistakes.db after the library parity
 * gate supplies an exact, learner-scoped identity. Once the paged projection is ready, current and
 * exact reads resolve that identity directly from the learner-bound student library; they never
 * fall back to legacy merely because the discardable projection omits full entries. No database is
 * joined or attached; the identity match happens in memory.
 */
internal class ParityGatedStudentMistakeDetailRepository(
    private val learnerId: String,
    private val student: StudentMistakeStoreMistakeDetailRepository,
    private val catalogState: StateFlow<StudentMistakeLibraryCatalogState>,
    private val transitionalHistoricalFallback: MistakeDetailRepository,
) : MistakeDetailRepository {
    init {
        require(learnerId.isNotBlank()) { "Learner id must not be blank" }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(errorBookEntryId: String): Flow<MistakeDetailState> {
        require(errorBookEntryId.isNotBlank()) { "errorBookEntryId must not be blank" }
        return catalogState.flatMapLatest { state ->
            when (val route = state.resolveCurrent(errorBookEntryId)) {
                CurrentReadRoute.Legacy ->
                    transitionalHistoricalFallback.observe(errorBookEntryId)

                CurrentReadRoute.NotFound -> failClosedDetailFlow()
                is CurrentReadRoute.LearnerBound ->
                    student.observeLearnerBound(StudentMistakeEntryId(route.entryId))

                is CurrentReadRoute.Student -> student.observeScoped(route.entry.toStudentQuery())
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeExact(key: MistakeRevisionKey): Flow<MistakeDetailState> =
        catalogState.flatMapLatest { state ->
            when (val route = state.resolveExact(key)) {
                ExactReadRoute.Legacy -> transitionalHistoricalFallback.observeExact(key)
                ExactReadRoute.NotFound -> failClosedDetailFlow()
                ExactReadRoute.LearnerBound ->
                    student.observeLearnerBound(StudentMistakeEntryId(key.entryId)).map { detailState ->
                        detailState.requireExact(key)
                    }

                is ExactReadRoute.Student ->
                    student.observeScoped(route.entry.toStudentQuery()).map { detailState ->
                        detailState.requireExact(key)
                    }
            }
        }

    override suspend fun readExact(key: MistakeRevisionKey): MistakeDetailState {
        return when (val route = catalogState.value.resolveExact(key)) {
            ExactReadRoute.Legacy -> transitionalHistoricalFallback.readExact(key)
            ExactReadRoute.NotFound -> MistakeDetailState.NotFound
            ExactReadRoute.LearnerBound ->
                student.readLearnerBound(StudentMistakeEntryId(key.entryId)).requireExact(key)

            is ExactReadRoute.Student ->
                student.readScoped(route.entry.toStudentQuery()).requireExact(key)
        }
    }

    override suspend fun readExact(
        keys: List<MistakeRevisionKey>,
    ): List<MistakeDetailState> {
        require(keys.size <= MAX_BATCH_SIZE) { "Mistake-detail batch is too large" }
        val gateSnapshot = catalogState.value
        if (gateSnapshot is StudentMistakeLibraryCatalogState.Ready) {
            return student.readLearnerBoundExactBatch(keys)
        }
        if (gateSnapshot !is StudentMistakeLibraryCatalogState.Verified) {
            return transitionalHistoricalFallback.readExact(keys)
        }
        val routes = keys.map { key -> gateSnapshot.resolveExact(key) }
        if (routes.any { route -> route == ExactReadRoute.NotFound }) {
            return List(keys.size) { MistakeDetailState.NotFound }
        }
        if (routes.any { route -> route == ExactReadRoute.Legacy }) {
            return transitionalHistoricalFallback.readExact(keys)
        }
        val studentRoutes = routes.filterIsInstance<ExactReadRoute.Student>()
        return student
            .readScopedBatch(studentRoutes.map { route -> route.entry.toStudentQuery() })
            .mapIndexed { index, detailState -> detailState.requireExact(keys[index]) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeRevisionHistory(
        errorBookEntryId: String,
    ): Flow<List<MistakeRevisionSummary>> {
        require(errorBookEntryId.isNotBlank()) { "errorBookEntryId must not be blank" }
        return catalogState.flatMapLatest { state ->
            when (val route = state.resolveCurrent(errorBookEntryId)) {
                CurrentReadRoute.Legacy ->
                    transitionalHistoricalFallback.observeRevisionHistory(errorBookEntryId)

                CurrentReadRoute.NotFound -> flow { emit(emptyList()) }
                is CurrentReadRoute.LearnerBound ->
                    student.observeLearnerBoundRevisionHistory(
                        StudentMistakeEntryId(route.entryId),
                    )

                is CurrentReadRoute.Student ->
                    student.observeRevisionHistoryScoped(route.entry.toStudentQuery())
            }
        }
    }

    private fun StudyCatalogEntry.toStudentQuery(): StudentMistakeDetailQuery {
        val resolvedSubject =
            runCatching { SubjectKind.valueOf(subject) }
                .getOrNull()
                ?.takeUnless { it == SubjectKind.GENERAL }
                ?: throw IllegalStateException("Verified mistake identity has an invalid subject")
        return StudentMistakeDetailQuery(
            learnerId = learnerId,
            problem =
                StudentProblemRef(
                    learnerId = learnerId,
                    subject = resolvedSubject,
                    problemId = problemId,
                    practiceUnitId = practiceUnitId,
                ),
            errorBookEntryId = entryId,
        )
    }

    private fun StudentMistakeLibraryCatalogState.resolveCurrent(
        errorBookEntryId: String,
    ): CurrentReadRoute =
        when (this) {
            is StudentMistakeLibraryCatalogState.Verified ->
                entries.resolveCurrent(errorBookEntryId)
                    ?.let(CurrentReadRoute::Student)
                    ?: CurrentReadRoute.NotFound

            is StudentMistakeLibraryCatalogState.Ready ->
                CurrentReadRoute.LearnerBound(errorBookEntryId)

            StudentMistakeLibraryCatalogState.WaitingForParity,
            is StudentMistakeLibraryCatalogState.Building,
            StudentMistakeLibraryCatalogState.ParityBlocked,
            StudentMistakeLibraryCatalogState.Unavailable,
            -> CurrentReadRoute.Legacy
        }

    private fun StudentMistakeLibraryCatalogState.resolveExact(
        key: MistakeRevisionKey,
    ): ExactReadRoute {
        if (this is StudentMistakeLibraryCatalogState.Ready) {
            return ExactReadRoute.LearnerBound
        }
        if (this !is StudentMistakeLibraryCatalogState.Verified) {
            return ExactReadRoute.Legacy
        }
        val current = entries.resolveCurrent(key.entryId) ?: return ExactReadRoute.NotFound
        if (current.problemId != key.problemId) return ExactReadRoute.NotFound
        return if (current.problemRevisionId == key.problemRevisionId) {
            ExactReadRoute.Student(current)
        } else {
            ExactReadRoute.Legacy
        }
    }

    private sealed interface CurrentReadRoute {
        data object Legacy : CurrentReadRoute

        data object NotFound : CurrentReadRoute

        data class LearnerBound(
            val entryId: String,
        ) : CurrentReadRoute

        data class Student(
            val entry: StudyCatalogEntry,
        ) : CurrentReadRoute
    }

    private sealed interface ExactReadRoute {
        data object Legacy : ExactReadRoute

        data object NotFound : ExactReadRoute

        data object LearnerBound : ExactReadRoute

        data class Student(
            val entry: StudyCatalogEntry,
        ) : ExactReadRoute
    }

    private fun failClosedDetailFlow(): Flow<MistakeDetailState> = flow {
        emit(MistakeDetailState.Loading)
        emit(MistakeDetailState.NotFound)
    }

    private companion object {
        const val MAX_BATCH_SIZE = 100
    }
}

private fun List<StudyCatalogEntry>.resolveCurrent(
    errorBookEntryId: String,
): StudyCatalogEntry? =
    singleOrNull { entry -> entry.entryId == errorBookEntryId }

private fun MistakeDetailState.requireExact(
    key: MistakeRevisionKey,
): MistakeDetailState =
    when (this) {
        MistakeDetailState.Loading,
        MistakeDetailState.NotFound,
        -> this

        is MistakeDetailState.CorruptSnapshot ->
            takeIf { identity.matches(key) } ?: MistakeDetailState.NotFound

        is MistakeDetailState.Legacy ->
            takeIf { detail.identity.matches(key) } ?: MistakeDetailState.NotFound

        is MistakeDetailState.Ready ->
            takeIf { detail.identity.matches(key) } ?: MistakeDetailState.NotFound
    }

private fun MistakeDetailIdentity.matches(
    key: MistakeRevisionKey,
): Boolean =
    errorBookEntryId == key.entryId &&
        problemId == key.problemId &&
        problemRevisionId == key.problemRevisionId
