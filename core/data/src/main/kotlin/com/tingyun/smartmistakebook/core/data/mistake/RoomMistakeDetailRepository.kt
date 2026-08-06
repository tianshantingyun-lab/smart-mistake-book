package com.tingyun.smartmistakebook.core.data.mistake

import android.content.Context
import com.tingyun.smartmistakebook.core.data.capture.AndroidCanonicalAssetVault
import com.tingyun.smartmistakebook.core.data.session.capture.LegacyRoomCaptureAssetBridge
import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.LegacyPreCutoverMistakeDetailReadPort
import com.tingyun.smartmistakebook.core.database.MistakeDetailRecord
import com.tingyun.smartmistakebook.core.database.MistakeDetailSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.MistakeRevisionSummaryRecord
import com.tingyun.smartmistakebook.core.domain.MistakeDetail
import com.tingyun.smartmistakebook.core.domain.MistakeDetailIdentity
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionSummary
import com.tingyun.smartmistakebook.core.domain.MistakeSourceAsset
import com.tingyun.smartmistakebook.core.domain.MistakeSourceLocation
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.domain.TutorConversationReference
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeStore
import com.tingyun.smartmistakebook.core.student.mistake.database.LearnerBoundStudentMistakeLibraryPort
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

internal fun interface MistakeDetailRecordReader {
    suspend fun read(errorBookEntryId: String): MistakeDetailRecord?
}

internal fun interface ExactMistakeDetailRecordReader {
    suspend fun read(key: MistakeRevisionKey): MistakeDetailRecord?
}

internal fun interface CurrentMistakeDetailBatchRecordReader {
    suspend fun read(entryIds: List<String>): List<MistakeDetailRecord>
}

internal fun interface CanonicalAssetUriResolver {
    fun resolve(sourceAsset: CanonicalSourceAssetRecord): String
}

internal fun interface MistakeRevisionHistoryReader {
    suspend fun read(errorBookEntryId: String): List<MistakeRevisionSummaryRecord>
}

internal class RoomMistakeDetailRepository(
    private val recordReader: MistakeDetailRecordReader,
    private val exactRecordReader: ExactMistakeDetailRecordReader,
    private val batchRecordReader: CurrentMistakeDetailBatchRecordReader? = null,
    private val revisionHistoryReader: MistakeRevisionHistoryReader =
        MistakeRevisionHistoryReader { emptyList() },
    private val assetUriResolver: CanonicalAssetUriResolver,
) : MistakeDetailRepository {
    override fun observe(errorBookEntryId: String): Flow<MistakeDetailState> {
        require(errorBookEntryId.isNotBlank()) { "errorBookEntryId must not be blank" }
        return flow {
            emit(MistakeDetailState.Loading)
            val record = recordReader.read(errorBookEntryId)
            emit(record?.toState() ?: MistakeDetailState.NotFound)
        }.flowOn(Dispatchers.IO)
    }

    override fun observeExact(key: MistakeRevisionKey): Flow<MistakeDetailState> = flow {
        emit(MistakeDetailState.Loading)
        emit(readExactState(key))
    }.flowOn(Dispatchers.IO)

    override suspend fun readExact(key: MistakeRevisionKey): MistakeDetailState =
        withContext(Dispatchers.IO) { readExactState(key) }

    override suspend fun readExact(
        keys: List<MistakeRevisionKey>,
    ): List<MistakeDetailState> = withContext(Dispatchers.IO) {
        require(keys.size <= MAX_BATCH_SIZE) { "Mistake-detail batch is too large" }
        if (keys.isEmpty()) return@withContext emptyList()
        val reader = batchRecordReader
            ?: return@withContext keys.map { key -> readExactState(key) }
        val recordsByEntryId = reader
            .read(keys.map(MistakeRevisionKey::entryId).distinct())
            .associateBy(MistakeDetailRecord::entryId)
        keys.map { key ->
            recordsByEntryId[key.entryId]
                ?.takeIf { record ->
                    record.problemId == key.problemId &&
                        record.problemRevisionId == key.problemRevisionId
                }
                ?.toState()
                ?: MistakeDetailState.NotFound
        }
    }

    override fun observeRevisionHistory(
        errorBookEntryId: String,
    ): Flow<List<MistakeRevisionSummary>> {
        require(errorBookEntryId.isNotBlank()) { "errorBookEntryId must not be blank" }
        return flow {
            val history = revisionHistoryReader.read(errorBookEntryId)
                .map { record -> record.toDomain() }
            check(history.count(MistakeRevisionSummary::isCurrent) <= 1) {
                "A mistake entry cannot have multiple current revisions"
            }
            emit(history)
        }.flowOn(Dispatchers.IO)
    }

    private suspend fun readExactState(key: MistakeRevisionKey): MistakeDetailState =
        exactRecordReader.read(key)?.toState() ?: MistakeDetailState.NotFound

    private fun MistakeDetailRecord.toState(): MistakeDetailState {
        val identity = MistakeDetailIdentity(
            errorBookEntryId = entryId,
            problemId = problemId,
            problemRevisionId = problemRevisionId,
            practiceUnitId = practiceUnitId,
            revisionNumber = revisionNumber,
            title = title,
            subject = subject,
        )
        val snapshot = questionDocumentSnapshot
        if (snapshot == null) {
            return MistakeDetailState.Legacy(toDetail(identity))
        }
        val document = decodeCommittedDocument(snapshot)
            ?: return MistakeDetailState.CorruptSnapshot(identity)
        if (CapturedQuestionDocumentFingerprint.of(document) != contentFingerprint) {
            return MistakeDetailState.CorruptSnapshot(identity)
        }
        return MistakeDetailState.Ready(
            detail = toDetail(identity),
            questionDocument = document,
        )
    }

    private fun MistakeDetailRecord.toDetail(identity: MistakeDetailIdentity) = MistakeDetail(
        identity = identity,
        fallbackMarkdown = problemMarkdown,
        source = if (sourceAssets.isEmpty()) {
            MistakeSourceSet.Missing
        } else {
            MistakeSourceSet.Present(sourceAssets.map { sourceAsset -> sourceAsset.toDomain() })
        },
        tutorConversation = tutorConversationReference(),
    )

    private fun MistakeDetailRecord.tutorConversationReference(): TutorConversationReference? {
        check((tutorSessionId == null) == (tutorQuestionRevisionNumber == null)) {
            "Tutor conversation locator must be complete"
        }
        val sessionId = tutorSessionId ?: return null
        return TutorConversationReference(
            sessionId = sessionId,
            questionRevisionNumber = checkNotNull(tutorQuestionRevisionNumber),
        )
    }

    private fun MistakeDetailSourceAssetRecord.toDomain(): MistakeSourceAsset =
        MistakeSourceAsset(
            role = role,
            sourceAssetId = sourceAsset.sourceAssetId,
            contentSha256 = sourceAsset.contentSha256,
            mimeType = sourceAsset.mimeType,
            byteSize = sourceAsset.byteSize,
            width = sourceAsset.width,
            height = sourceAsset.height,
            sourceType = sourceAsset.sourceType,
            createdAtEpochMillis = sourceAsset.createdAtEpochMillis,
            location = resolveLocation(sourceAsset),
        )

    private fun resolveLocation(sourceAsset: CanonicalSourceAssetRecord): MistakeSourceLocation =
        try {
            val localUri = assetUriResolver.resolve(sourceAsset)
            require(URI(localUri).scheme == "file") { "Canonical asset URI must use file scheme" }
            MistakeSourceLocation.Available(localUri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            MistakeSourceLocation.Unavailable
        }

    private fun decodeCommittedDocument(snapshot: String): CapturedQuestionDocument? = try {
        CapturedQuestionDocumentCodec.decode(snapshot).takeIf { document ->
            CapturedQuestionDocumentValidator.validateForCommit(document).isEmpty()
        }
    } catch (_: Exception) {
        null
    }

    private fun MistakeRevisionSummaryRecord.toDomain() = MistakeRevisionSummary(
        entryId = entryId,
        problemId = problemId,
        problemRevisionId = problemRevisionId,
        revisionNumber = revisionNumber,
        title = title,
        createdAtEpochMillis = createdAtEpochMillis,
        isCurrent = isCurrent,
    )

    private companion object {
        const val MAX_BATCH_SIZE = 100
    }
}

object MistakeDetailRepositoryFactory {
    internal fun create(
        studentMistakeStore: StudentMistakeStore,
    ): MistakeDetailRepository =
        StudentMistakeStoreMistakeDetailRepository(
            StudentMistakeStoreDetailReadPort(studentMistakeStore),
        )

    internal fun createParityGatedReadTransition(
        studentMistakeStore: StudentMistakeStore,
        learnerBoundLibrary: LearnerBoundStudentMistakeLibraryPort,
        learnerId: String,
        catalogState: StateFlow<StudentMistakeLibraryCatalogState>,
        transitionalHistoricalFallback: MistakeDetailRepository,
    ): MistakeDetailRepository =
        ParityGatedStudentMistakeDetailRepository(
            learnerId = learnerId,
            student =
                StudentMistakeStoreMistakeDetailRepository(
                    StudentMistakeStoreDetailReadPort(studentMistakeStore),
                    exactDetailLibrary = learnerBoundLibrary,
                    expectedLearnerId = learnerId,
                ),
            catalogState = catalogState,
            transitionalHistoricalFallback = transitionalHistoricalFallback,
        )

    /**
     * Keeps the legacy mistake authority readable until a verified cutover switches the route.
     *
     * This is intentionally named: callers must not mistake a successful mirror for an authority
     * transfer while legacy writers are still active.
     */
    fun createLegacyDuringAuthorityMigration(
        context: Context,
        legacyMistakes: LegacyPreCutoverMistakeDetailReadPort,
    ): MistakeDetailRepository {
        val assetVault =
            LegacyRoomCaptureAssetBridge(
                AndroidCanonicalAssetVault(context.applicationContext),
            )
        return RoomMistakeDetailRepository(
            recordReader = MistakeDetailRecordReader(legacyMistakes::readMistakeDetail),
            exactRecordReader = ExactMistakeDetailRecordReader { key ->
                legacyMistakes.readExactMistakeDetail(
                    entryId = key.entryId,
                    problemId = key.problemId,
                    problemRevisionId = key.problemRevisionId,
                )
            },
            batchRecordReader = CurrentMistakeDetailBatchRecordReader(
                legacyMistakes::readCurrentMistakeDetails,
            ),
            revisionHistoryReader = MistakeRevisionHistoryReader(
                legacyMistakes::readMistakeRevisionHistory,
            ),
            assetUriResolver = CanonicalAssetUriResolver { sourceAsset ->
                assetVault.resolve(sourceAsset).toURI().toASCIIString()
            },
        )
    }

    fun createDeferred(
        repositoryProvider: suspend () -> MistakeDetailRepository,
    ): MistakeDetailRepository =
        DeferredMistakeDetailRepository(repositoryProvider)
}

private class DeferredMistakeDetailRepository(
    private val repositoryProvider: suspend () -> MistakeDetailRepository,
) : MistakeDetailRepository {
    override fun observe(errorBookEntryId: String): Flow<MistakeDetailState> =
        flow { emitAll(repositoryProvider().observe(errorBookEntryId)) }

    override fun observeExact(key: MistakeRevisionKey): Flow<MistakeDetailState> =
        flow { emitAll(repositoryProvider().observeExact(key)) }

    override suspend fun readExact(key: MistakeRevisionKey): MistakeDetailState =
        repositoryProvider().readExact(key)

    override suspend fun readExact(
        keys: List<MistakeRevisionKey>,
    ): List<MistakeDetailState> = repositoryProvider().readExact(keys)

    override fun observeRevisionHistory(
        errorBookEntryId: String,
    ): Flow<List<MistakeRevisionSummary>> =
        flow { emitAll(repositoryProvider().observeRevisionHistory(errorBookEntryId)) }
}
