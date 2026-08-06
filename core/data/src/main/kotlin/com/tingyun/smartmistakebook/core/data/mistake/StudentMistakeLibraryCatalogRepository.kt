package com.tingyun.smartmistakebook.core.data.mistake

import android.content.Context
import com.tingyun.smartmistakebook.core.data.authority.LocalLearningAuthorityRuntime
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayPageRequest
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayPageResult
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayRevision
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.student.mistake.database.LearnerBoundStudentMistakeLibraryPort
import com.tingyun.smartmistakebook.core.student.mistake.database.MAX_LIBRARY_PAGE_SIZE
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryItem
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryCursor
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryPageRequest
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeLibraryPageResult
import java.io.Closeable
import kotlin.jvm.JvmSynthetic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/**
 * Read-only student-mistake catalog state.
 *
 * [Verified] means every entry came from one stable learner-bound student-mistake snapshot. The
 * high-school knowledge store may contribute reviewed display names, but it cannot create, remove,
 * or mutate mistake entries. A learner-bound mastery reader may contribute display status from one
 * fixed revision; missing, stale, or conflicting mastery data remains [MasteryStatus.UNKNOWN].
 *
 * [Verified] is retained only for the legacy compatibility adapter. [Ready] carries revision and
 * count only; production callers page and filter through this repository's bounded read methods.
 */
sealed interface StudentMistakeLibraryCatalogState {
    data object WaitingForParity : StudentMistakeLibraryCatalogState

    data class Verified(
        val entries: List<StudyCatalogEntry>,
    ) : StudentMistakeLibraryCatalogState

    /** Progress only; entries live in the discardable projection database, never in this state. */
    data class Building(
        val revision: StudentMistakeCatalogRevision,
        val progress: StudentMistakeCatalogBuildProgress,
    ) : StudentMistakeLibraryCatalogState

    /** Revision and count only; callers page through [StudentMistakeLibraryCatalogRepository]. */
    data class Ready(
        val revision: StudentMistakeCatalogRevision,
        val entryCount: Long,
    ) : StudentMistakeLibraryCatalogState {
        init {
            require(entryCount >= 0L) { "Catalog entry count must not be negative" }
        }
    }

    data object ParityBlocked : StudentMistakeLibraryCatalogState

    data object Unavailable : StudentMistakeLibraryCatalogState

    fun verifiedEntriesOrNull(): List<StudyCatalogEntry>? =
        (this as? Verified)?.entries
}

interface StudentMistakeLibraryCatalogRepository : Closeable {
    val state: StateFlow<StudentMistakeLibraryCatalogState>

    suspend fun readPage(
        request: StudentMistakeCatalogPageRequest = StudentMistakeCatalogPageRequest(),
    ): StudentMistakeCatalogPageResult = StudentMistakeCatalogPageResult.ReloadRequired

    suspend fun readFacets(
        filter: StudentMistakeCatalogFilter = StudentMistakeCatalogFilter(),
    ): StudentMistakeCatalogFacetResult = StudentMistakeCatalogFacetResult.ReloadRequired

    suspend fun snapshotExport(
        filter: StudentMistakeCatalogFilter = StudentMistakeCatalogFilter(),
    ): StudentMistakeCatalogExportResult = StudentMistakeCatalogExportResult.ReloadRequired
}

object StudentMistakeLibraryCatalogRepositoryFactory {
    /**
     * Opens the v1 DERIVED/DISCARDABLE/NON_AUTHORITATIVE projection. This path never calls the
     * legacy complete-list compatibility helper. Mastery and knowledge arrive as revision-bearing
     * StateFlows, so each emission already contains the exact value that cancels stale staging work.
     * The knowledge resolver receives that same build revision and must fence every lookup to it.
     */
    @JvmSynthetic
    internal fun createProjected(
        context: Context,
        expectedLearnerId: String,
        applicationScope: CoroutineScope,
        libraryProvider: suspend () -> LearnerBoundStudentMistakeLibraryPort,
        masteryDisplayRevisions: StateFlow<LearnerMasteryDisplayRevision>,
        masteryRead: LearnerBoundStudentMistakeMasteryRead,
        knowledgeRevisions: StateFlow<StudentMistakeKnowledgeCatalogRevision>,
        knowledgeDisplayResolver:
            suspend (
                StudentMistakeKnowledgeCatalogRevision,
                List<KnowledgeNodeRef>,
            ) -> Map<KnowledgeNodeRef, String>,
        nowEpochMillis: () -> Long = System::currentTimeMillis,
    ): StudentMistakeLibraryCatalogRepository =
        createProjected(
            expectedLearnerId = expectedLearnerId,
            applicationScope = applicationScope,
            projectionStoreProvider = {
                StudentMistakeCatalogProjectionDatabaseFactory.open(context.applicationContext)
            },
            libraryProvider = libraryProvider,
            masteryDisplayRevisions = masteryDisplayRevisions,
            masteryRead = masteryRead,
            knowledgeRevisions = knowledgeRevisions,
            knowledgeDisplayResolver = knowledgeDisplayResolver,
            nowEpochMillis = nowEpochMillis,
        )

    @JvmSynthetic
    internal fun createProjected(
        expectedLearnerId: String,
        applicationScope: CoroutineScope,
        projectionStoreProvider:
            suspend () -> DiscardableStudentMistakeCatalogProjectionStore,
        libraryProvider: suspend () -> LearnerBoundStudentMistakeLibraryPort,
        masteryDisplayRevisions: StateFlow<LearnerMasteryDisplayRevision>,
        masteryRead: LearnerBoundStudentMistakeMasteryRead,
        knowledgeRevisions: StateFlow<StudentMistakeKnowledgeCatalogRevision>,
        knowledgeDisplayResolver:
            suspend (
                StudentMistakeKnowledgeCatalogRevision,
                List<KnowledgeNodeRef>,
            ) -> Map<KnowledgeNodeRef, String>,
        nowEpochMillis: () -> Long = System::currentTimeMillis,
    ): StudentMistakeLibraryCatalogRepository =
        ProjectedStudentMistakeLibraryCatalogRepository(
            expectedLearnerId = expectedLearnerId,
            projectionStoreProvider = projectionStoreProvider,
            libraryProvider = libraryProvider,
            masteryDisplayRevisions = masteryDisplayRevisions,
            masteryRead = masteryRead,
            knowledgeRevisions = knowledgeRevisions,
            knowledgeDisplayResolver = knowledgeDisplayResolver,
            applicationScope = applicationScope,
            nowEpochMillis = nowEpochMillis,
        )

    fun createDeferredFromAuthorityRuntime(
        expectedLearnerId: String,
        applicationScope: CoroutineScope,
        runtimeProvider: suspend () -> LocalLearningAuthorityRuntime,
    ): StudentMistakeLibraryCatalogRepository =
        DeferredStudentMistakeLibraryCatalogRepository(
            expectedLearnerId = expectedLearnerId,
            sourceProvider = {
                val runtime = runtimeProvider()
                StudentMistakeCatalogSources(
                    library = runtime.studentMistakeLibraryPort(),
                    knowledgeDisplayResolver =
                        runtime::resolveStudentMistakeKnowledgeDisplayNames,
                )
            },
            applicationScope = applicationScope,
        )

    @JvmSynthetic
    internal fun createDeferred(
        expectedLearnerId: String,
        applicationScope: CoroutineScope,
        libraryProvider: suspend () -> LearnerBoundStudentMistakeLibraryPort,
        knowledgeDisplayResolver:
            suspend (List<KnowledgeNodeRef>) -> Map<KnowledgeNodeRef, String> = { emptyMap() },
    ): StudentMistakeLibraryCatalogRepository =
        DeferredStudentMistakeLibraryCatalogRepository(
            expectedLearnerId = expectedLearnerId,
            sourceProvider = {
                StudentMistakeCatalogSources(
                    library = libraryProvider(),
                    knowledgeDisplayResolver = knowledgeDisplayResolver,
                )
            },
            applicationScope = applicationScope,
        )

    /**
     * Production seam for the three-authority catalog projection.
     *
     * [masteryRead] is already bound to [expectedLearnerId]. It receives only exact knowledge
     * references saved with mistakes and cannot select a learner or write to either authority.
     */
    @JvmSynthetic
    internal fun createDeferredWithMastery(
        expectedLearnerId: String,
        applicationScope: CoroutineScope,
        masteryDisplayRevision: LearnerMasteryDisplayRevision,
        libraryProvider: suspend () -> LearnerBoundStudentMistakeLibraryPort,
        masteryRead: LearnerBoundStudentMistakeMasteryRead,
        knowledgeDisplayResolver:
            suspend (List<KnowledgeNodeRef>) -> Map<KnowledgeNodeRef, String> = { emptyMap() },
    ): StudentMistakeLibraryCatalogRepository =
        createDeferredWithMastery(
            expectedLearnerId = expectedLearnerId,
            applicationScope = applicationScope,
            masteryDisplayRevisionProvider = { masteryDisplayRevision },
            libraryProvider = libraryProvider,
            masteryRead = masteryRead,
            knowledgeDisplayResolver = knowledgeDisplayResolver,
        )

    /**
     * Deferred production overload. The revision provider runs once inside the catalog's own
     * background job, so synchronous capability assembly never blocks on a database flow.
     */
    @JvmSynthetic
    internal fun createDeferredWithMastery(
        expectedLearnerId: String,
        applicationScope: CoroutineScope,
        masteryDisplayRevisionProvider: suspend () -> LearnerMasteryDisplayRevision,
        libraryProvider: suspend () -> LearnerBoundStudentMistakeLibraryPort,
        masteryRead: LearnerBoundStudentMistakeMasteryRead,
        knowledgeDisplayResolver:
            suspend (List<KnowledgeNodeRef>) -> Map<KnowledgeNodeRef, String> = { emptyMap() },
    ): StudentMistakeLibraryCatalogRepository =
        DeferredStudentMistakeLibraryCatalogRepository(
            expectedLearnerId = expectedLearnerId,
            sourceProvider = {
                val masterySource =
                    try {
                        StudentMistakeCatalogMasterySource(
                            revision = masteryDisplayRevisionProvider(),
                            read = masteryRead,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                StudentMistakeCatalogSources(
                    library = libraryProvider(),
                    knowledgeDisplayResolver = knowledgeDisplayResolver,
                    masterySource = masterySource,
                )
            },
            applicationScope = applicationScope,
        )
}

internal fun interface LearnerBoundStudentMistakeMasteryRead {
    suspend fun read(request: LearnerMasteryDisplayPageRequest): LearnerMasteryDisplayPageResult
}

private data class StudentMistakeCatalogSources(
    val library: LearnerBoundStudentMistakeLibraryPort,
    val knowledgeDisplayResolver:
        suspend (List<KnowledgeNodeRef>) -> Map<KnowledgeNodeRef, String>,
    val masterySource: StudentMistakeCatalogMasterySource? = null,
)

private data class StudentMistakeCatalogMasterySource(
    val revision: LearnerMasteryDisplayRevision,
    val read: LearnerBoundStudentMistakeMasteryRead,
)

private class DeferredStudentMistakeLibraryCatalogRepository(
    expectedLearnerId: String,
    sourceProvider: suspend () -> StudentMistakeCatalogSources,
    applicationScope: CoroutineScope,
) : StudentMistakeLibraryCatalogRepository {
    init {
        require(expectedLearnerId.isNotBlank()) { "Expected learner id must not be blank" }
    }

    private val mutableState =
        MutableStateFlow<StudentMistakeLibraryCatalogState>(
            StudentMistakeLibraryCatalogState.WaitingForParity,
        )
    override val state: StateFlow<StudentMistakeLibraryCatalogState> = mutableState.asStateFlow()

    private val observationJob: Job = applicationScope.launch {
        try {
            val sources = sourceProvider()
            val library = sources.library
            library.observeChangeVersion().collectLatest { studentChangeVersion ->
                mutableState.value = StudentMistakeLibraryCatalogState.WaitingForParity
                mutableState.value =
                    try {
                        readStableStudentCatalog(
                            library = library,
                            expectedLearnerId = expectedLearnerId,
                            expectedStudentChangeVersion = studentChangeVersion,
                            knowledgeDisplayResolver = sources.knowledgeDisplayResolver,
                            masteryDisplayRevision = sources.masterySource?.revision,
                            masteryRead = sources.masterySource?.read,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        StudentMistakeLibraryCatalogState.Unavailable
                    }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            mutableState.value = StudentMistakeLibraryCatalogState.Unavailable
        }
    }

    override fun close() {
        observationJob.cancel()
    }
}

private class ProjectedStudentMistakeLibraryCatalogRepository(
    private val expectedLearnerId: String,
    private val projectionStoreProvider:
        suspend () -> DiscardableStudentMistakeCatalogProjectionStore,
    private val libraryProvider: suspend () -> LearnerBoundStudentMistakeLibraryPort,
    private val masteryDisplayRevisions: StateFlow<LearnerMasteryDisplayRevision>,
    private val masteryRead: LearnerBoundStudentMistakeMasteryRead,
    private val knowledgeRevisions: StateFlow<StudentMistakeKnowledgeCatalogRevision>,
    private val knowledgeDisplayResolver:
        suspend (
            StudentMistakeKnowledgeCatalogRevision,
            List<KnowledgeNodeRef>,
        ) -> Map<KnowledgeNodeRef, String>,
    applicationScope: CoroutineScope,
    private val nowEpochMillis: () -> Long,
) : StudentMistakeLibraryCatalogRepository {
    init {
        require(expectedLearnerId.isNotBlank()) { "Expected learner id must not be blank" }
    }

    private val learnerFingerprint = expectedLearnerId.studentMistakeCatalogLearnerFingerprint()
    private val mutableState =
        MutableStateFlow<StudentMistakeLibraryCatalogState>(
            StudentMistakeLibraryCatalogState.WaitingForParity,
        )
    override val state: StateFlow<StudentMistakeLibraryCatalogState> = mutableState.asStateFlow()

    @Volatile
    private var projectionStore: DiscardableStudentMistakeCatalogProjectionStore? = null

    @Volatile
    private var studentLibrary: LearnerBoundStudentMistakeLibraryPort? = null

    @Volatile
    private var readyGeneration: DerivedStudentMistakeCatalogGeneration? = null

    private val projectionStoreMutex = Mutex()
    private val recoveryEpoch = MutableStateFlow(0L)
    private val readyPublicationMonitor = Any()
    private var closed = false

    private val observationJob: Job = applicationScope.launch {
        var sessionFailureCount = 0
        while (true) {
            try {
                observeProjectionSession()
                sessionFailureCount = 0
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                readyGeneration = null
                mutableState.value = StudentMistakeLibraryCatalogState.WaitingForParity
                delay(retryDelayMillis(sessionFailureCount++))
            } finally {
                closeProjectionSession()
            }
        }
    }

    override suspend fun readPage(
        request: StudentMistakeCatalogPageRequest,
    ): StudentMistakeCatalogPageResult {
        val building = mutableState.value as? StudentMistakeLibraryCatalogState.Building
        if (building != null) {
            return StudentMistakeCatalogPageResult.Preparing(building.progress)
        }
        val ready = currentReadyGenerationOrNull()
            ?: return StudentMistakeCatalogPageResult.ReloadRequired
        val page =
            try {
                withProjectionStore { store ->
                    store.readPage(
                        generation = ready,
                        learnerFingerprint = learnerFingerprint,
                        revision = ready.revision,
                        filter = request.filter,
                        cursor = request.cursor,
                        limit = request.limit,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ProjectionStoreFailure) {
                requestProjectionRecovery()
                null
            } ?: return StudentMistakeCatalogPageResult.ReloadRequired
        return StudentMistakeCatalogPageResult.Content(
            revision = ready.revision,
            items = page.rows.map(DerivedStudentMistakeCatalogEntryEntity::toCatalogItem),
            nextCursor = page.nextCursor,
        )
    }

    override suspend fun readFacets(
        filter: StudentMistakeCatalogFilter,
    ): StudentMistakeCatalogFacetResult {
        val building = mutableState.value as? StudentMistakeLibraryCatalogState.Building
        if (building != null) {
            return StudentMistakeCatalogFacetResult.Preparing(building.progress)
        }
        val ready = currentReadyGenerationOrNull()
            ?: return StudentMistakeCatalogFacetResult.ReloadRequired
        val facets =
            try {
                withProjectionStore { store ->
                    store.readFacets(
                        generation = ready,
                        learnerFingerprint = learnerFingerprint,
                        revision = ready.revision,
                        filter = filter,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ProjectionStoreFailure) {
                requestProjectionRecovery()
                null
            } ?: return StudentMistakeCatalogFacetResult.ReloadRequired
        return StudentMistakeCatalogFacetResult.Content(
            revision = ready.revision,
            totalCount = facets.totalCount,
            subjects =
                facets.subjects.map { row ->
                    StudentMistakeCatalogFacetCount(enumValueOf(row.subject), row.problemCount)
                },
            sections =
                facets.sections.map { row ->
                    StudentMistakeCatalogFacetCount(row.sectionStableId, row.problemCount)
                },
            knowledgeNodes =
                facets.knowledge.map { row ->
                    StudentMistakeCatalogFacetCount(
                        KnowledgeNodeRef(
                            subject = enumValueOf(row.knowledgeSubject),
                            knowledgeNodeId = row.knowledgeNodeId,
                            taxonomyVersion = row.knowledgeTaxonomyVersion,
                            knowledgePackVersion = row.knowledgePackVersion,
                        ),
                        row.problemCount,
                    )
                },
            masteryStatuses =
                facets.mastery.map { row ->
                    StudentMistakeCatalogFacetCount(enumValueOf(row.masteryStatus), row.problemCount)
                },
            truncatedDimensions = facets.truncatedDimensions,
        )
    }

    override suspend fun snapshotExport(
        filter: StudentMistakeCatalogFilter,
    ): StudentMistakeCatalogExportResult {
        val building = mutableState.value as? StudentMistakeLibraryCatalogState.Building
        if (building != null) {
            return StudentMistakeCatalogExportResult.Preparing(building.progress)
        }
        val ready = currentReadyGenerationOrNull()
            ?: return StudentMistakeCatalogExportResult.ReloadRequired
        val rows =
            try {
                withProjectionStore { store ->
                    store.readExportRows(
                        generation = ready,
                        learnerFingerprint = learnerFingerprint,
                        revision = ready.revision,
                        filter = filter,
                        limit = MAX_STUDENT_MISTAKE_CATALOG_EXPORT_ITEMS + 1,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: ProjectionStoreFailure) {
                requestProjectionRecovery()
                null
            } ?: return StudentMistakeCatalogExportResult.ReloadRequired
        if (rows.size > MAX_STUDENT_MISTAKE_CATALOG_EXPORT_ITEMS) {
            return StudentMistakeCatalogExportResult.TooMany(ready.revision)
        }
        val items = rows.map(DerivedStudentMistakeCatalogEntryEntity::toCatalogItem)
        if (
            items.sumOf(StudentMistakeCatalogItem::catalogPayloadUtf8Bytes) >
            MAX_STUDENT_MISTAKE_CATALOG_EXPORT_UTF8_BYTES
        ) {
            return StudentMistakeCatalogExportResult.PayloadTooLarge(ready.revision)
        }
        return StudentMistakeCatalogExportResult.Content(revision = ready.revision, items = items)
    }

    private suspend fun currentReadyGenerationOrNull():
        DerivedStudentMistakeCatalogGeneration? {
        val ready = readyGeneration ?: return null
        if (mutableState.value !is StudentMistakeLibraryCatalogState.Ready) return null
        val currentRevision =
            try {
                readCurrentRevision(studentLibrary ?: return null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return null
        return ready.takeIf { it.revision == currentRevision }
    }

    private suspend fun observeProjectionSession() {
        installProjectionStore(projectionStoreProvider())
        try {
            withProjectionStore { store -> store.discardBuildingGenerations() }
        } catch (failure: ProjectionStoreFailure) {
            rebuildProjectionStore(failure)
            withProjectionStore { store -> store.discardBuildingGenerations() }
        }
        val library = libraryProvider()
        studentLibrary = library
        var latestAcceptedRevision: StudentMistakeCatalogRevision? = null
        var handledRecoveryEpoch = -1L
        combine(
            library.observeChangeVersion(),
            masteryDisplayRevisions,
            knowledgeRevisions,
            recoveryEpoch,
        ) { studentChangeVersion, masteryRevision, knowledgeRevision, requestedRecovery ->
            ObservedStudentMistakeCatalogRevision(
                revision =
                    studentMistakeCatalogRevision(
                        studentChangeVersion,
                        masteryRevision,
                        knowledgeRevision,
                    ),
                recoveryEpoch = requestedRecovery,
            )
        }.collectLatest { observed ->
            val previous = latestAcceptedRevision
            if (previous != null && !observed.revision.isMonotonicSuccessorOf(previous)) {
                readyGeneration = null
                mutableState.value = StudentMistakeLibraryCatalogState.ParityBlocked
                return@collectLatest
            }
            latestAcceptedRevision = observed.revision
            if (observed.recoveryEpoch != handledRecoveryEpoch) {
                if (handledRecoveryEpoch >= 0L) rebuildProjectionStore()
                handledRecoveryEpoch = observed.recoveryEpoch
            }
            buildWithRecovery(library, observed.revision)
        }
    }

    private suspend fun buildWithRecovery(
        library: LearnerBoundStudentMistakeLibraryPort,
        revision: StudentMistakeCatalogRevision,
    ) {
        var failureCount = 0
        while (true) {
            val current =
                try {
                    readCurrentRevision(library)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            if (current != revision) {
                readyGeneration = null
                mutableState.value = StudentMistakeLibraryCatalogState.WaitingForParity
                return
            }
            try {
                when (buildOrReuseGeneration(library, revision)) {
                    ProjectedBuildOutcome.Ready -> return
                    ProjectedBuildOutcome.RevisionChanged -> return
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: StudentMistakeCatalogPayloadException) {
                readyGeneration = null
                mutableState.value = StudentMistakeLibraryCatalogState.ParityBlocked
                return
            } catch (failure: ProjectionStoreFailure) {
                readyGeneration = null
                mutableState.value = StudentMistakeLibraryCatalogState.WaitingForParity
                rebuildProjectionStore(failure)
            } catch (_: Exception) {
                readyGeneration = null
                mutableState.value = StudentMistakeLibraryCatalogState.WaitingForParity
            }
            delay(retryDelayMillis(failureCount++))
        }
    }

    private suspend fun buildOrReuseGeneration(
        library: LearnerBoundStudentMistakeLibraryPort,
        revision: StudentMistakeCatalogRevision,
    ): ProjectedBuildOutcome {
        readyGeneration = null
        mutableState.value = StudentMistakeLibraryCatalogState.WaitingForParity
        val cached =
            withProjectionStore { store -> store.findReady(learnerFingerprint, revision) }
        cached?.let {
            if (readCurrentRevision(library) != revision) {
                mutableState.value = StudentMistakeLibraryCatalogState.WaitingForParity
                return ProjectedBuildOutcome.RevisionChanged
            }
            return if (publishReady(cached)) {
                ProjectedBuildOutcome.Ready
            } else {
                ProjectedBuildOutcome.RevisionChanged
            }
        }

        val generation =
            withProjectionStore { store ->
                store.beginBuilding(learnerFingerprint, revision, nowEpochMillis())
            }
        mutableState.value =
            StudentMistakeLibraryCatalogState.Building(
                revision = revision,
                progress = StudentMistakeCatalogBuildProgress(0L),
            )
        var promoted = false
        try {
            var indexedEntryCount = 0L
            var cursor: StudentMistakeLibraryCursor? = null
            do {
                val page =
                    when (
                        val result =
                            library.readPage(
                                StudentMistakeLibraryPageRequest(
                                    cursor = cursor,
                                    limit = MAX_LIBRARY_PAGE_SIZE,
                                ),
                            )
                    ) {
                        is StudentMistakeLibraryPageResult.Content -> result
                        StudentMistakeLibraryPageResult.ReloadRequired,
                        is StudentMistakeLibraryPageResult.Preparing,
                        -> {
                            mutableState.value = StudentMistakeLibraryCatalogState.ParityBlocked
                            return ProjectedBuildOutcome.RevisionChanged
                        }
                    }
                check(page.items.size <= MAX_LIBRARY_PAGE_SIZE) {
                    "Student authority exceeded the requested catalog page budget"
                }
                if (page.nextCursor != null) {
                    check(page.items.isNotEmpty() && page.nextCursor != cursor) {
                        "Student authority returned an invalid continuation page"
                    }
                }
                val batch = try {
                    projectStudentPage(
                        generationId = generation.generationId,
                        items = page.items,
                        masteryRevision = revision.toMasteryRevision(),
                        knowledgeRevision = revision.toKnowledgeRevision(),
                    ) ?: return ProjectedBuildOutcome.RevisionChanged
                } catch (failure: IllegalArgumentException) {
                    throw StudentMistakeCatalogPayloadException(failure)
                } catch (failure: IllegalStateException) {
                    throw StudentMistakeCatalogPayloadException(failure)
                }
                batch.stagingBatches().forEach { boundedBatch ->
                    indexedEntryCount += boundedBatch.size
                    withProjectionStore { store ->
                        store.appendBatch(
                            generation = generation,
                            batch = boundedBatch,
                            indexedEntryCount = indexedEntryCount,
                            nowEpochMillis = nowEpochMillis(),
                        )
                    }
                    mutableState.value =
                        StudentMistakeLibraryCatalogState.Building(
                            revision = revision,
                            progress = StudentMistakeCatalogBuildProgress(indexedEntryCount),
                        )
                }
                cursor = page.nextCursor
            } while (cursor != null)

            val finalRevision = readCurrentRevision(library)
            if (finalRevision != revision) {
                mutableState.value = StudentMistakeLibraryCatalogState.WaitingForParity
                return ProjectedBuildOutcome.RevisionChanged
            }
            if (
                !withProjectionStore { store -> store.promote(generation, nowEpochMillis()) }
            ) {
                return ProjectedBuildOutcome.RevisionChanged
            }
            promoted = true
            val ready = generation.copy(indexedEntryCount = indexedEntryCount)
            return if (publishReady(ready)) {
                ProjectedBuildOutcome.Ready
            } else {
                ProjectedBuildOutcome.RevisionChanged
            }
        } finally {
            if (!promoted) {
                withContext(NonCancellable) {
                    try {
                        withProjectionStore { store -> store.discard(generation.generationId) }
                    } catch (_: ProjectionStoreFailure) {
                        requestProjectionRecovery()
                    }
                }
            }
        }
    }

    private suspend fun projectStudentPage(
        generationId: String,
        items: List<StudentMistakeLibraryItem>,
        masteryRevision: LearnerMasteryDisplayRevision,
        knowledgeRevision: StudentMistakeKnowledgeCatalogRevision,
    ): List<DerivedStudentMistakeCatalogBatchEntry>? {
        if (
            items.map { it.entryId.value }.distinct().size != items.size ||
            items.any { item ->
                item.problemRevision.problem.learnerId != expectedLearnerId ||
                    item.subject != item.problemRevision.problem.subject ||
                    item.knowledgeNodes.any { it.subject != item.subject } ||
                    item.sections.size > MAX_STUDENT_MISTAKE_CATALOG_RELATIONSHIPS_PER_ITEM ||
                    item.knowledgeNodes.size >
                    MAX_STUDENT_MISTAKE_CATALOG_RELATIONSHIPS_PER_ITEM
            }
        ) {
            throw IllegalStateException("Student authority returned an invalid catalog page")
        }
        val knowledgeRefs =
            items.asSequence()
                .flatMap { it.knowledgeNodes.asSequence() }
                .distinct()
                .toList()
        val displayNames = linkedMapOf<KnowledgeNodeRef, String>()
        knowledgeRefs.chunked(MAX_STUDENT_MISTAKE_CATALOG_PAGE_SIZE).forEach { batch ->
            val resolved =
                try {
                    knowledgeDisplayResolver(knowledgeRevision, batch)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    throw StudentMistakeCatalogSourceFailure(failure)
                }
            require(resolved.keys.all(batch.toHashSet()::contains)) {
                "Knowledge display resolver returned an unrequested reference"
            }
            require(
                resolved.values.all { name ->
                    name.isNotBlank() &&
                        name.length <= MAX_KNOWLEDGE_DISPLAY_NAME_CHARS &&
                        name.none(Char::isISOControl)
                },
            ) { "Knowledge display resolver returned an invalid display name" }
            displayNames.putAll(resolved)
        }
        val masteryByEntryId = readFixedMasteryBatch(items, masteryRevision) ?: return null
        return items.map { item ->
            val title = item.title?.takeIf(String::isNotBlank) ?: item.practiceUnitTitle
            val sections = item.sections.map { it.key.value }.distinct().sorted()
            val nodes = item.knowledgeNodes.distinctBy(KnowledgeNodeRef::canonicalFingerprint)
            val labels = nodes.mapNotNull(displayNames::get).distinct()
            require(labels.size <= MAX_STUDENT_MISTAKE_CATALOG_RELATIONSHIPS_PER_ITEM) {
                "Student catalog entry has too many knowledge display names"
            }
            val search =
                DerivedStudentMistakeSearchNormalizer.document(
                    title = title,
                    stemPreview = item.stemPreview,
                    practiceUnitTitle = item.practiceUnitTitle,
                )
            DerivedStudentMistakeCatalogBatchEntry(
                entry =
                    DerivedStudentMistakeCatalogEntryEntity(
                        generationId = generationId,
                        entryId = item.entryId.value,
                        problemId = item.problemRevision.problem.problemId,
                        problemRevisionId = item.problemRevision.revisionId,
                        practiceUnitId = item.problemRevision.problem.practiceUnitId,
                        subject = item.subject.name,
                        title = title,
                        practiceUnitTitle = item.practiceUnitTitle,
                        sectionStableIdsWire = ProjectionWireCodec.encodeStrings(sections),
                        knowledgeNodesWire = ProjectionWireCodec.encodeKnowledgeNodes(nodes),
                        knowledgeDisplayNamesWire = ProjectionWireCodec.encodeStrings(labels),
                        masteryStatus =
                            (masteryByEntryId[item.entryId.value] ?: MasteryStatus.UNKNOWN).name,
                        favorite = item.collection.favorite,
                        changedAtEpochMillis = item.collection.changedAtEpochMillis,
                        normalizedSearchText = search.normalizedText,
                        tokenizedSearchText = search.tokenizedText,
                    ),
                sectionStableIds = sections,
                knowledgeNodes =
                    nodes.map { node ->
                        DerivedStudentMistakeCatalogKnowledgeDraft(
                            subject = node.subject.name,
                            knowledgeNodeId = node.knowledgeNodeId,
                            taxonomyVersion = node.taxonomyVersion,
                            knowledgePackVersion = node.knowledgePackVersion,
                        )
                    },
            )
        }
    }

    private suspend fun readFixedMasteryBatch(
        items: List<StudentMistakeLibraryItem>,
        expectedRevision: LearnerMasteryDisplayRevision,
    ): Map<String, MasteryStatus>? {
        val bindings =
            items.map { item ->
                StudentMistakeMasteryBinding(item.entryId.value, item.knowledgeNodes)
            }
        val batches = ArrayList<StudentMistakeMasteryDisplayBatch>()
        groupStudentMistakeMasteryRequests(bindings).forEach { (subject, references) ->
            references.chunked(LearnerMasteryDisplayPageRequest.MAX_LIMIT).forEach { batch ->
                when (
                    val result =
                        masteryRead.read(
                            LearnerMasteryDisplayPageRequest(
                                subject = subject,
                                expectedRevision = expectedRevision,
                                orderedKnowledgeNodes = batch,
                            ),
                        )
                ) {
                    is LearnerMasteryDisplayPageResult.Current -> {
                        if (result.revision != expectedRevision) return null
                        batches += StudentMistakeMasteryDisplayBatch(result.revision, result.items)
                    }

                    is LearnerMasteryDisplayPageResult.RevisionChanged -> return null
                }
            }
        }
        return projectStudentMistakeMastery(bindings, expectedRevision, batches).statusByMistakeId
    }

    private suspend fun readCurrentRevision(
        library: LearnerBoundStudentMistakeLibraryPort,
    ): StudentMistakeCatalogRevision =
        studentMistakeCatalogRevision(
            studentChangeVersion = library.observeChangeVersion().first(),
            mastery = masteryDisplayRevisions.value,
            knowledge = knowledgeRevisions.value,
        )

    private suspend fun installProjectionStore(
        store: DiscardableStudentMistakeCatalogProjectionStore,
    ) {
        projectionStoreMutex.lock()
        try {
            projectionStore?.close()
            projectionStore = store
        } finally {
            projectionStoreMutex.unlock()
        }
    }

    private suspend fun <T> withProjectionStore(
        operation: suspend (DiscardableStudentMistakeCatalogProjectionStore) -> T,
    ): T {
        projectionStoreMutex.lock()
        try {
            val store = projectionStore
                ?: throw ProjectionStoreFailure(IllegalStateException("Projection store is closed"))
            return try {
                operation(store)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ProjectionStoreFailure) {
                throw failure
            } catch (failure: Exception) {
                throw ProjectionStoreFailure(failure)
            }
        } finally {
            projectionStoreMutex.unlock()
        }
    }

    private suspend fun rebuildProjectionStore(
        previousFailure: ProjectionStoreFailure? = null,
    ) {
        projectionStoreMutex.lock()
        try {
            val current = projectionStore
            projectionStore = null
            val rebuilt =
                try {
                    current?.rebuildAfterCorruption() ?: projectionStoreProvider()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    previousFailure?.addSuppressed(failure)
                    throw previousFailure ?: ProjectionStoreFailure(failure)
                }
            projectionStore = rebuilt
            rebuilt.discardBuildingGenerations()
        } finally {
            projectionStoreMutex.unlock()
        }
    }

    private fun requestProjectionRecovery() {
        recoveryEpoch.update { current -> if (current == Long.MAX_VALUE) 0L else current + 1L }
    }

    private fun publishReady(
        generation: DerivedStudentMistakeCatalogGeneration,
    ): Boolean =
        synchronized(readyPublicationMonitor) {
            if (closed) return@synchronized false
            readyGeneration = generation
            mutableState.value =
                StudentMistakeLibraryCatalogState.Ready(
                    revision = generation.revision,
                    entryCount = generation.indexedEntryCount,
                )
            true
        }

    private suspend fun closeProjectionSession() {
        withContext(NonCancellable) {
            projectionStoreMutex.lock()
            try {
                projectionStore?.close()
                projectionStore = null
                studentLibrary = null
            } finally {
                projectionStoreMutex.unlock()
            }
        }
    }

    override fun close() {
        synchronized(readyPublicationMonitor) {
            closed = true
            readyGeneration = null
            mutableState.value = StudentMistakeLibraryCatalogState.WaitingForParity
        }
        observationJob.cancel()
    }
}

private fun StudentMistakeCatalogRevision.toMasteryRevision(): LearnerMasteryDisplayRevision =
    LearnerMasteryDisplayRevision(
        ledgerSequence = masteryLedgerSequence,
        asOfEpochMillis = masteryAsOfEpochMillis,
    )

private fun StudentMistakeCatalogRevision.toKnowledgeRevision():
    StudentMistakeKnowledgeCatalogRevision =
    StudentMistakeKnowledgeCatalogRevision(
        activationGeneration = knowledgeActivationGeneration,
        manifestFingerprint = knowledgeManifestFingerprint,
        taxonomyVersion = knowledgeTaxonomyVersion,
        knowledgePackVersion = knowledgePackVersion,
    )

private enum class ProjectedBuildOutcome {
    Ready,
    RevisionChanged,
}

private data class ObservedStudentMistakeCatalogRevision(
    val revision: StudentMistakeCatalogRevision,
    val recoveryEpoch: Long,
)

private class ProjectionStoreFailure(
    cause: Throwable,
) : Exception(cause)

private class StudentMistakeCatalogPayloadException(
    cause: Throwable,
) : Exception(cause)

private class StudentMistakeCatalogSourceFailure(
    cause: Throwable,
) : Exception(cause)

private fun studentMistakeCatalogRevision(
    studentChangeVersion: Long,
    mastery: LearnerMasteryDisplayRevision,
    knowledge: StudentMistakeKnowledgeCatalogRevision,
): StudentMistakeCatalogRevision =
    StudentMistakeCatalogRevision(
        studentChangeVersion = studentChangeVersion,
        masteryLedgerSequence = mastery.ledgerSequence,
        masteryAsOfEpochMillis = mastery.asOfEpochMillis,
        knowledgeActivationGeneration = knowledge.activationGeneration,
        knowledgeManifestFingerprint = knowledge.manifestFingerprint,
        knowledgeTaxonomyVersion = knowledge.taxonomyVersion,
        knowledgePackVersion = knowledge.knowledgePackVersion,
    )

private fun StudentMistakeCatalogRevision.isMonotonicSuccessorOf(
    previous: StudentMistakeCatalogRevision,
): Boolean =
    studentChangeVersion >= previous.studentChangeVersion &&
        masteryLedgerSequence >= previous.masteryLedgerSequence &&
        masteryAsOfEpochMillis >= previous.masteryAsOfEpochMillis &&
        (
            knowledgeActivationGeneration > previous.knowledgeActivationGeneration ||
                (
                    knowledgeActivationGeneration == previous.knowledgeActivationGeneration &&
                        knowledgeManifestFingerprint == previous.knowledgeManifestFingerprint &&
                        knowledgeTaxonomyVersion == previous.knowledgeTaxonomyVersion &&
                        knowledgePackVersion == previous.knowledgePackVersion
                )
        )

private fun List<DerivedStudentMistakeCatalogBatchEntry>.stagingBatches():
    List<List<DerivedStudentMistakeCatalogBatchEntry>> {
    if (isEmpty()) return emptyList()
    val batches = mutableListOf<List<DerivedStudentMistakeCatalogBatchEntry>>()
    var current = mutableListOf<DerivedStudentMistakeCatalogBatchEntry>()
    var currentBytes = 0L
    for (draft in this) {
        val entryBytes = draft.entry.storedPayloadUtf8Bytes()
        require(entryBytes <= MAX_STUDENT_MISTAKE_CATALOG_PAGE_UTF8_BYTES)
        if (current.isNotEmpty() && currentBytes + entryBytes > MAX_STUDENT_MISTAKE_CATALOG_PAGE_UTF8_BYTES) {
            batches += current
            current = mutableListOf()
            currentBytes = 0L
        }
        current += draft
        currentBytes += entryBytes
    }
    if (current.isNotEmpty()) batches += current
    return batches
}

private fun retryDelayMillis(failureCount: Int): Long =
    25L *
        (1L shl failureCount.coerceIn(0, MAX_CATALOG_RETRY_BACKOFF_EXPONENT))

/** Legacy/full-snapshot compatibility adapter. New production code must use createProjected. */
internal suspend fun readStableStudentCatalog(
    library: LearnerBoundStudentMistakeLibraryPort,
    expectedLearnerId: String,
    expectedStudentChangeVersion: Long? = null,
    knowledgeDisplayResolver:
        suspend (List<KnowledgeNodeRef>) -> Map<KnowledgeNodeRef, String> = { emptyMap() },
    masteryDisplayRevision: LearnerMasteryDisplayRevision? = null,
    masteryRead: LearnerBoundStudentMistakeMasteryRead? = null,
): StudentMistakeLibraryCatalogState {
    require(expectedLearnerId.isNotBlank()) { "Expected learner id must not be blank" }
    require((masteryDisplayRevision == null) == (masteryRead == null)) {
        "Mistake mastery projection requires both a fixed revision and a learner-bound reader"
    }
    val initialChangeVersion = library.readFacets().changeVersion
    if (
        expectedStudentChangeVersion != null &&
        initialChangeVersion != expectedStudentChangeVersion
    ) {
        return StudentMistakeLibraryCatalogState.ParityBlocked
    }
    val studentItems = readCompleteStudentLibrary(library)
        ?: return StudentMistakeLibraryCatalogState.ParityBlocked
    if (
        studentItems.any { item ->
            item.problemRevision.problem.learnerId != expectedLearnerId ||
                item.subject != item.problemRevision.problem.subject ||
                item.knowledgeNodes.any { ref -> ref.subject != item.subject }
        }
    ) {
        return StudentMistakeLibraryCatalogState.ParityBlocked
    }
    val finalChangeVersion = library.readFacets().changeVersion
    if (
        finalChangeVersion != initialChangeVersion ||
        (
            expectedStudentChangeVersion != null &&
                finalChangeVersion != expectedStudentChangeVersion
        )
    ) {
        return StudentMistakeLibraryCatalogState.ParityBlocked
    }
    val distinctKnowledgeRefs =
        studentItems
            .asSequence()
            .flatMap { item -> item.knowledgeNodes.asSequence() }
            .distinct()
            .toList()
    val displayNames = knowledgeDisplayResolver(distinctKnowledgeRefs)
    val requestedKnowledgeRefs = distinctKnowledgeRefs.toHashSet()
    require(displayNames.keys.all(requestedKnowledgeRefs::contains)) {
        "Knowledge display resolver returned an unrequested reference"
    }
    require(
        displayNames.values.all { name ->
            name.isNotBlank() &&
                name.length <= MAX_KNOWLEDGE_DISPLAY_NAME_CHARS &&
                name.none(Char::isISOControl)
        },
    ) {
        "Knowledge display resolver returned an invalid learner-facing name"
    }
    val masteryByEntryId =
        readStudentMistakeMasteryProjection(
            studentItems = studentItems,
            expectedRevision = masteryDisplayRevision,
            masteryRead = masteryRead,
        )
    return StudentMistakeLibraryCatalogState.Verified(
        studentItems.map { item ->
            item.toCatalogEntry(
                knowledgeDisplayNames = displayNames,
                masteryStatus = masteryByEntryId[item.entryId.value] ?: MasteryStatus.UNKNOWN,
            )
        },
    )
}

private suspend fun readStudentMistakeMasteryProjection(
    studentItems: List<StudentMistakeLibraryItem>,
    expectedRevision: LearnerMasteryDisplayRevision?,
    masteryRead: LearnerBoundStudentMistakeMasteryRead?,
): Map<String, MasteryStatus> {
    if (expectedRevision == null || masteryRead == null) return emptyMap()
    return try {
        val bindings =
            studentItems.map { item ->
                StudentMistakeMasteryBinding(
                    mistakeId = item.entryId.value,
                    knowledgeNodes = item.knowledgeNodes,
                )
            }
        val batches = ArrayList<StudentMistakeMasteryDisplayBatch>()
        groupStudentMistakeMasteryRequests(bindings).forEach { (subject, references) ->
            references.chunked(LearnerMasteryDisplayPageRequest.MAX_LIMIT).forEach { batch ->
                when (
                    val result =
                        masteryRead.read(
                            LearnerMasteryDisplayPageRequest(
                                subject = subject,
                                expectedRevision = expectedRevision,
                                orderedKnowledgeNodes = batch,
                            ),
                        )
                ) {
                    is LearnerMasteryDisplayPageResult.Current ->
                        batches +=
                            StudentMistakeMasteryDisplayBatch(
                                revision = result.revision,
                                items = result.items,
                            )

                    is LearnerMasteryDisplayPageResult.RevisionChanged -> return emptyMap()
                }
            }
        }
        projectStudentMistakeMastery(
            bindings = bindings,
            expectedRevision = expectedRevision,
            masteryBatches = batches,
        ).statusByMistakeId
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyMap()
    }
}

private suspend fun readCompleteStudentLibrary(
    library: LearnerBoundStudentMistakeLibraryPort,
): List<StudentMistakeLibraryItem>? {
    val items = ArrayList<StudentMistakeLibraryItem>()
    val visitedEntryIds = hashSetOf<String>()
    val visitedCursors = hashSetOf<StudentMistakeLibraryCursor>()
    var cursor: StudentMistakeLibraryCursor? = null
    do {
        when (
            val page =
                library.readPage(
                    StudentMistakeLibraryPageRequest(
                        cursor = cursor,
                        limit = MAX_LIBRARY_PAGE_SIZE,
                    ),
                )
        ) {
            is StudentMistakeLibraryPageResult.Content -> {
                if (
                    page.items.any { item ->
                        !visitedEntryIds.add(item.entryId.value)
                    }
                ) {
                    return null
                }
                items += page.items
                if (items.size > LEGACY_MAX_FULL_SNAPSHOT_ENTRIES) return null
                cursor = page.nextCursor
                if (cursor != null && !visitedCursors.add(cursor)) return null
                if (cursor != null && page.items.isEmpty()) return null
            }

            StudentMistakeLibraryPageResult.ReloadRequired,
            is StudentMistakeLibraryPageResult.Preparing,
            -> return null
        }
    } while (cursor != null)
    return items
}

private fun StudentMistakeLibraryItem.toCatalogEntry(
    knowledgeDisplayNames: Map<KnowledgeNodeRef, String>,
    masteryStatus: MasteryStatus,
): StudyCatalogEntry =
    StudyCatalogEntry(
        entryId = entryId.value,
        problemId = problemRevision.problem.problemId,
        problemRevisionId = problemRevision.revisionId,
        practiceUnitId = problemRevision.problem.practiceUnitId,
        subject = subject.name,
        title = title?.takeIf(String::isNotBlank) ?: practiceUnitTitle,
        problemMarkdown = stemPreview,
        sourceKey = null,
        isCuratedExample = false,
        chapterLabels = listOf(practiceUnitTitle).filter(String::isNotBlank),
        knowledgeLabels =
            knowledgeNodes.mapNotNull(knowledgeDisplayNames::get).distinct(),
        masteryStatus = masteryStatus,
        nextReviewAtEpochMillis = null,
        retrievability = null,
    )

private const val LEGACY_MAX_FULL_SNAPSHOT_ENTRIES = 20_000
private const val MAX_KNOWLEDGE_DISPLAY_NAME_CHARS = 256
private const val MAX_CATALOG_RETRY_BACKOFF_EXPONENT = 5
