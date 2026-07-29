package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.AppendProblemDraftSourceAssetCommand
import com.tingyun.smartmistakebook.core.database.AppendProblemDraftSourceAssetResult
import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.CommitProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.CommitProblemDraftResult
import com.tingyun.smartmistakebook.core.database.CommitTutorSessionCommand
import com.tingyun.smartmistakebook.core.database.ConfirmTutorSessionCommand
import com.tingyun.smartmistakebook.core.database.CreateProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.DatabaseContractValidator
import com.tingyun.smartmistakebook.core.database.EndTutorSessionCommand
import com.tingyun.smartmistakebook.core.database.EndTutorSessionResult
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.ProblemDraftCommitReceipt
import com.tingyun.smartmistakebook.core.database.ProblemDraftRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftRevisionRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftWriteResult
import com.tingyun.smartmistakebook.core.database.ProblemDraftReplacementResult
import com.tingyun.smartmistakebook.core.database.ProblemDraftSplitResult
import com.tingyun.smartmistakebook.core.database.ReplaceProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.ReviseProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.SplitProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.database.TutorSessionRecord
import com.tingyun.smartmistakebook.core.database.TutorSessionWriteResult
import com.tingyun.smartmistakebook.core.database.entity.CanonicalSourceAssetEntity
import com.tingyun.smartmistakebook.core.database.entity.ErrorBookEntryEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemDraftCommitReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemDraftEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemDraftRevisionEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemDraftSourceAssetEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemOrganizationWorkEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRevisionEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRevisionSourceAssetEntity
import com.tingyun.smartmistakebook.core.database.entity.TutorSessionEntity
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrantCodec
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

internal data class ExactCommittedProblemRow(
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "problem_revision_id")
    val problemRevisionId: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "error_book_entry_id")
    val errorBookEntryId: String?,
    @ColumnInfo(name = "entry_updated_at_epoch_millis")
    val entryUpdatedAtEpochMillis: Long?,
)

@Dao
internal abstract class ProblemDraftTransactionDao {
    @Query("SELECT COUNT(*) FROM problem_draft WHERE status = 'EDITING'")
    abstract fun observePendingDraftCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSourceAsset(entity: CanonicalSourceAssetEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertDraft(entity: ProblemDraftEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertDraftSourceAsset(
        entity: ProblemDraftSourceAssetEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertDraftRevision(entity: ProblemDraftRevisionEntity): Long

    @Insert
    protected abstract suspend fun insertProblem(entity: ProblemEntity)

    @Insert
    protected abstract suspend fun insertProblemRevision(entity: ProblemRevisionEntity)

    @Insert
    protected abstract suspend fun insertPracticeUnit(entity: PracticeUnitEntity)

    @Insert
    protected abstract suspend fun insertErrorBookEntry(entity: ErrorBookEntryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertRevisionSourceAsset(
        entity: ProblemRevisionSourceAssetEntity,
    ): Long

    @Insert
    protected abstract suspend fun insertCommitReceipt(entity: ProblemDraftCommitReceiptEntity)

    @Insert
    protected abstract suspend fun insertProblemOrganizationWork(
        entity: ProblemOrganizationWorkEntity,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertTutorSession(entity: TutorSessionEntity): Long

    @Query("SELECT * FROM canonical_source_asset WHERE source_asset_id = :sourceAssetId LIMIT 1")
    protected abstract suspend fun findSourceAsset(sourceAssetId: String): CanonicalSourceAssetEntity?

    @Query("SELECT * FROM problem_draft WHERE draft_id = :draftId LIMIT 1")
    protected abstract suspend fun findDraftEntity(draftId: String): ProblemDraftEntity?

    @Query(
        "SELECT * FROM problem_draft_source_asset WHERE draft_id = :draftId ORDER BY page_index ASC",
    )
    protected abstract suspend fun findDraftSourceAssets(
        draftId: String,
    ): List<ProblemDraftSourceAssetEntity>

    @Query(
        """
        UPDATE problem_draft
        SET updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE draft_id = :draftId
          AND current_revision_number = :expectedRevisionNumber
          AND status = 'EDITING'
        """,
    )
    protected abstract suspend fun touchDraftAfterSourceAppend(
        draftId: String,
        expectedRevisionNumber: Int,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        SELECT * FROM problem_draft_revision
        WHERE draft_id = :draftId AND revision_number = :revisionNumber
        LIMIT 1
        """,
    )
    protected abstract suspend fun findDraftRevision(
        draftId: String,
        revisionNumber: Int,
    ): ProblemDraftRevisionEntity?

    @Query(
        "SELECT * FROM problem_draft_commit_receipt WHERE command_id = :commandId LIMIT 1",
    )
    protected abstract suspend fun findCommitReceiptByCommand(
        commandId: String,
    ): ProblemDraftCommitReceiptEntity?

    @Query(
        "SELECT * FROM problem_draft_commit_receipt WHERE draft_id = :draftId LIMIT 1",
    )
    protected abstract suspend fun findCommitReceiptByDraft(
        draftId: String,
    ): ProblemDraftCommitReceiptEntity?

    @Query(
        """
        SELECT
            problem.problem_id,
            revision.revision_id AS problem_revision_id,
            unit.practice_unit_id,
            entry.entry_id AS error_book_entry_id,
            entry.updated_at_epoch_millis AS entry_updated_at_epoch_millis
        FROM problem AS problem
        JOIN problem_revision AS revision
            ON revision.problem_id = problem.problem_id
        JOIN practice_unit AS unit
            ON unit.problem_revision_id = revision.revision_id
           AND unit.unit_key = 'whole-problem'
        LEFT JOIN error_book_entry AS entry
            ON entry.practice_unit_id = unit.practice_unit_id
        WHERE problem.canonical_fingerprint = :canonicalFingerprint
           OR (
                problem.subject = :subject
                AND revision.problem_markdown = :problemMarkdown
           )
        ORDER BY revision.revision_number DESC
        LIMIT 1
        """,
    )
    protected abstract suspend fun findExactCommittedProblem(
        canonicalFingerprint: String,
        subject: String,
        problemMarkdown: String,
    ): ExactCommittedProblemRow?

    @Query(
        """
        UPDATE error_book_entry
        SET status = 'ACTIVE',
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE entry_id = :entryId
        """,
    )
    protected abstract suspend fun reactivateErrorBookEntry(
        entryId: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("SELECT * FROM tutor_session WHERE session_id = :sessionId LIMIT 1")
    protected abstract suspend fun findTutorSessionById(sessionId: String): TutorSessionEntity?

    @Query("SELECT * FROM tutor_session WHERE draft_id = :draftId LIMIT 1")
    protected abstract suspend fun findTutorSessionByDraft(draftId: String): TutorSessionEntity?

    @Query(
        """
        UPDATE problem_draft
        SET current_revision_number = :nextRevisionNumber,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE draft_id = :draftId
          AND current_revision_number = :expectedRevisionNumber
          AND status = 'EDITING'
        """,
    )
    protected abstract suspend fun advanceDraftHead(
        draftId: String,
        expectedRevisionNumber: Int,
        nextRevisionNumber: Int,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE problem_draft
        SET status = 'COMMITTED',
            updated_at_epoch_millis = :committedAtEpochMillis
        WHERE draft_id = :draftId
          AND current_revision_number = :expectedRevisionNumber
          AND status = 'EDITING'
        """,
    )
    protected abstract suspend fun markDraftCommitted(
        draftId: String,
        expectedRevisionNumber: Int,
        committedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE problem_draft
        SET status = 'ABANDONED',
            updated_at_epoch_millis = :abandonedAtEpochMillis
        WHERE draft_id = :draftId
          AND current_revision_number = :expectedRevisionNumber
          AND status = 'EDITING'
        """,
    )
    protected abstract suspend fun markDraftAbandoned(
        draftId: String,
        expectedRevisionNumber: Int,
        abandonedAtEpochMillis: Long,
    ): Int

    @Transaction
    open suspend fun create(command: CreateProblemDraftCommand): ProblemDraftWriteResult {
        DatabaseContractValidator.validateCreateProblemDraft(command)
        val asset = command.sourceAsset.toEntity()
        insertSourceAsset(asset)
        if (findSourceAsset(asset.sourceAssetId)?.hasSameCanonicalContent(asset) != true) {
            throw ImmutablePayloadConflictException("canonical_source_asset", asset.sourceAssetId)
        }

        val draft = ProblemDraftEntity(
            draftId = command.draftId,
            sourceAssetId = asset.sourceAssetId,
            origin = command.origin,
            status = StudyDbValue.ProblemDraftStatus.EDITING,
            currentRevisionNumber = 1,
            createdAtEpochMillis = command.initialRevision.createdAtEpochMillis,
            updatedAtEpochMillis = command.initialRevision.createdAtEpochMillis,
            requestFingerprint = command.requestFingerprint,
        )
        val revision = command.initialRevision.toEntity()
        val created = insertDraft(draft) != -1L
        insertDraftSourceAsset(
            ProblemDraftSourceAssetEntity(
                draftId = draft.draftId,
                pageIndex = 0,
                sourceAssetId = asset.sourceAssetId,
                attachedAtEpochMillis = draft.createdAtEpochMillis,
            ),
        )
        insertDraftRevision(revision)

        if (findDraftEntity(draft.draftId) != draft || findDraftRevision(draft.draftId, 1) != revision) {
            throw ImmutablePayloadConflictException("problem_draft", draft.draftId)
        }
        return ProblemDraftWriteResult(created = created, draft = loadDraft(draft.draftId))
    }

    @Transaction
    open suspend fun appendSourceAsset(
        command: AppendProblemDraftSourceAssetCommand,
    ): AppendProblemDraftSourceAssetResult {
        DatabaseContractValidator.validateAppendProblemDraftSourceAsset(command)
        val asset = command.sourceAsset.toEntity()
        insertSourceAsset(asset)
        if (findSourceAsset(asset.sourceAssetId)?.hasSameCanonicalContent(asset) != true) {
            throw ImmutablePayloadConflictException("canonical_source_asset", asset.sourceAssetId)
        }

        val draft = findDraftEntity(command.draftId)
            ?: throw ImmutablePayloadConflictException("problem_draft", command.draftId)
        val current = findDraftSourceAssets(command.draftId)
        current.singleOrNull { it.pageIndex == command.expectedSourceAssetCount }?.let { existing ->
            if (existing.sourceAssetId != asset.sourceAssetId) {
                throw ImmutablePayloadConflictException("problem_draft_source_page", command.draftId)
            }
            return AppendProblemDraftSourceAssetResult(
                created = false,
                draft = loadDraft(command.draftId),
            )
        }
        if (
            draft.status != StudyDbValue.ProblemDraftStatus.EDITING ||
            draft.currentRevisionNumber != command.expectedRevisionNumber ||
            current.size != command.expectedSourceAssetCount ||
            current.map { it.pageIndex } != current.indices.toList() ||
            command.appendedAtEpochMillis < draft.updatedAtEpochMillis
        ) {
            throw ImmutablePayloadConflictException("problem_draft_source_head", command.draftId)
        }
        if (current.any { it.sourceAssetId == asset.sourceAssetId }) {
            throw ImmutablePayloadConflictException("problem_draft_source_asset", asset.sourceAssetId)
        }
        val totalPixels = current.sumOf { binding ->
            val source = checkNotNull(findSourceAsset(binding.sourceAssetId))
            source.width.toLong() * source.height
        } + asset.width.toLong() * asset.height
        if (totalPixels > MAX_CAPTURE_BUNDLE_PIXELS) {
            throw ImmutablePayloadConflictException("problem_draft_source_pixel_budget", command.draftId)
        }
        val binding = ProblemDraftSourceAssetEntity(
            draftId = command.draftId,
            pageIndex = command.expectedSourceAssetCount,
            sourceAssetId = asset.sourceAssetId,
            attachedAtEpochMillis = command.appendedAtEpochMillis,
        )
        if (insertDraftSourceAsset(binding) == -1L) {
            throw ImmutablePayloadConflictException("problem_draft_source_page", command.draftId)
        }
        if (
            touchDraftAfterSourceAppend(
                draftId = command.draftId,
                expectedRevisionNumber = command.expectedRevisionNumber,
                updatedAtEpochMillis = command.appendedAtEpochMillis,
            ) != 1
        ) {
            throw ImmutablePayloadConflictException("problem_draft_source_head", command.draftId)
        }
        return AppendProblemDraftSourceAssetResult(
            created = true,
            draft = loadDraft(command.draftId),
        )
    }

    @Transaction
    open suspend fun revise(command: ReviseProblemDraftCommand): ProblemDraftWriteResult {
        DatabaseContractValidator.validateReviseProblemDraft(command)
        val draft = findDraftEntity(command.draftId)
            ?: throw ImmutablePayloadConflictException("problem_draft", command.draftId)
        val revision = command.revision.toEntity()
        val sourceAssetIds = findDraftSourceAssets(command.draftId).mapTo(mutableSetOf()) {
            it.sourceAssetId
        }
        if (command.revision.questionDocument.blockEvidence.any { it.sourceAssetId !in sourceAssetIds }) {
            throw ImmutablePayloadConflictException("problem_draft_source_asset", command.draftId)
        }

        if (draft.currentRevisionNumber == revision.revisionNumber) {
            if (findDraftRevision(command.draftId, revision.revisionNumber) != revision) {
                throw ImmutablePayloadConflictException("problem_draft_revision", command.draftId)
            }
            return ProblemDraftWriteResult(created = false, draft = loadDraft(command.draftId))
        }
        if (
            draft.status != StudyDbValue.ProblemDraftStatus.EDITING ||
            draft.currentRevisionNumber != command.expectedRevisionNumber
        ) {
            throw ImmutablePayloadConflictException("problem_draft_head", command.draftId)
        }

        if (insertDraftRevision(revision) == -1L) {
            throw ImmutablePayloadConflictException("problem_draft_revision", command.draftId)
        }
        if (
            advanceDraftHead(
                draftId = command.draftId,
                expectedRevisionNumber = command.expectedRevisionNumber,
                nextRevisionNumber = revision.revisionNumber,
                updatedAtEpochMillis = revision.createdAtEpochMillis,
            ) != 1
        ) {
            throw ImmutablePayloadConflictException("problem_draft_head", command.draftId)
        }
        return ProblemDraftWriteResult(created = true, draft = loadDraft(command.draftId))
    }

    @Transaction
    open suspend fun replace(
        command: ReplaceProblemDraftCommand,
    ): ProblemDraftReplacementResult {
        DatabaseContractValidator.validateReplaceProblemDraft(command)
        val replaced = findDraftEntity(command.replacedDraftId)
            ?: throw ImmutablePayloadConflictException("problem_draft", command.replacedDraftId)
        val existingReplacement = findDraftEntity(command.replacement.draftId)

        if (replaced.status == StudyDbValue.ProblemDraftStatus.ABANDONED) {
            if (
                replaced.currentRevisionNumber != command.expectedReplacedRevisionNumber ||
                replaced.updatedAtEpochMillis != command.replacedAtEpochMillis ||
                existingReplacement == null ||
                !replacementMatches(command, existingReplacement)
            ) {
                throw ImmutablePayloadConflictException(
                    "problem_draft_replacement",
                    command.replacedDraftId,
                )
            }
            return ProblemDraftReplacementResult(
                created = false,
                replacement = loadDraft(existingReplacement.draftId),
            )
        }

        if (
            replaced.status != StudyDbValue.ProblemDraftStatus.EDITING ||
            replaced.currentRevisionNumber != command.expectedReplacedRevisionNumber ||
            findCommitReceiptByDraft(command.replacedDraftId) != null ||
            findTutorSessionByDraft(command.replacedDraftId) != null ||
            existingReplacement != null ||
            replaced.origin != command.replacement.origin
        ) {
            throw ImmutablePayloadConflictException(
                "problem_draft_replacement",
                command.replacedDraftId,
            )
        }

        val replacement = create(command.replacement)
        if (!replacement.created) {
            throw ImmutablePayloadConflictException(
                "problem_draft_replacement",
                command.replacement.draftId,
            )
        }
        if (
            markDraftAbandoned(
                draftId = command.replacedDraftId,
                expectedRevisionNumber = command.expectedReplacedRevisionNumber,
                abandonedAtEpochMillis = command.replacedAtEpochMillis,
            ) != 1
        ) {
            throw ImmutablePayloadConflictException(
                "problem_draft_head",
                command.replacedDraftId,
            )
        }
        return ProblemDraftReplacementResult(created = true, replacement = replacement.draft)
    }

    @Transaction
    open suspend fun split(command: SplitProblemDraftCommand): ProblemDraftSplitResult {
        DatabaseContractValidator.validateSplitProblemDraft(command)
        val replaced = findDraftEntity(command.replacedDraftId)
            ?: throw ImmutablePayloadConflictException("problem_draft", command.replacedDraftId)
        val existingReplacements = command.replacements.map { replacement ->
            findDraftEntity(replacement.draftId)
        }

        if (replaced.status == StudyDbValue.ProblemDraftStatus.ABANDONED) {
            if (
                replaced.currentRevisionNumber != command.expectedReplacedRevisionNumber ||
                replaced.updatedAtEpochMillis != command.splitAtEpochMillis ||
                existingReplacements.any { it == null } ||
                command.replacements.zip(existingReplacements.filterNotNull()).any {
                        (expected, existing),
                    -> !draftMatchesCreate(expected, existing, command.splitAtEpochMillis)
                }
            ) {
                throw ImmutablePayloadConflictException("problem_draft_split", command.replacedDraftId)
            }
            return ProblemDraftSplitResult(
                created = false,
                replacements = existingReplacements.filterNotNull().map { loadDraft(it.draftId) },
            )
        }

        if (
            replaced.status != StudyDbValue.ProblemDraftStatus.EDITING ||
            replaced.currentRevisionNumber != command.expectedReplacedRevisionNumber ||
            findCommitReceiptByDraft(command.replacedDraftId) != null ||
            findTutorSessionByDraft(command.replacedDraftId) != null ||
            existingReplacements.any { it != null } ||
            command.replacements.any { it.origin != replaced.origin }
        ) {
            throw ImmutablePayloadConflictException("problem_draft_split", command.replacedDraftId)
        }

        val replacements = command.replacements.map { replacement ->
            create(replacement).also { result ->
                if (!result.created) {
                    throw ImmutablePayloadConflictException("problem_draft_split", replacement.draftId)
                }
            }.draft
        }
        if (
            markDraftAbandoned(
                draftId = command.replacedDraftId,
                expectedRevisionNumber = command.expectedReplacedRevisionNumber,
                abandonedAtEpochMillis = command.splitAtEpochMillis,
            ) != 1
        ) {
            throw ImmutablePayloadConflictException("problem_draft_head", command.replacedDraftId)
        }
        return ProblemDraftSplitResult(created = true, replacements = replacements)
    }

    @Transaction
    open suspend fun mergeSourceBundle(
        primaryDraftId: String,
        expectedPrimaryRevisionNumber: Int,
        followingDraftId: String,
        expectedFollowingRevisionNumber: Int,
        mergedAtEpochMillis: Long,
    ): ProblemDraftRecord {
        require(primaryDraftId.isNotBlank() && followingDraftId.isNotBlank())
        require(primaryDraftId != followingDraftId)
        require(expectedPrimaryRevisionNumber > 0 && expectedFollowingRevisionNumber > 0)
        require(mergedAtEpochMillis >= 0)
        val primary = findDraftEntity(primaryDraftId)
            ?: throw ImmutablePayloadConflictException("problem_draft", primaryDraftId)
        val following = findDraftEntity(followingDraftId)
            ?: throw ImmutablePayloadConflictException("problem_draft", followingDraftId)
        if (
            primary.status != StudyDbValue.ProblemDraftStatus.EDITING ||
            following.status != StudyDbValue.ProblemDraftStatus.EDITING ||
            primary.currentRevisionNumber != expectedPrimaryRevisionNumber ||
            following.currentRevisionNumber != expectedFollowingRevisionNumber ||
            primary.origin != following.origin ||
            findCommitReceiptByDraft(primaryDraftId) != null ||
            findCommitReceiptByDraft(followingDraftId) != null ||
            findTutorSessionByDraft(primaryDraftId) != null ||
            findTutorSessionByDraft(followingDraftId) != null ||
            mergedAtEpochMillis < maxOf(primary.updatedAtEpochMillis, following.updatedAtEpochMillis)
        ) {
            throw ImmutablePayloadConflictException("problem_draft_bundle_merge", primaryDraftId)
        }

        val followingSources = findDraftSourceAssets(followingDraftId)
        var sourceCount = findDraftSourceAssets(primaryDraftId).size
        followingSources.forEach { binding ->
            val source = checkNotNull(findSourceAsset(binding.sourceAssetId)).toRecord()
            appendSourceAsset(
                AppendProblemDraftSourceAssetCommand(
                    draftId = primaryDraftId,
                    expectedRevisionNumber = expectedPrimaryRevisionNumber,
                    expectedSourceAssetCount = sourceCount,
                    sourceAsset = source,
                    appendedAtEpochMillis = mergedAtEpochMillis,
                ),
            )
            sourceCount += 1
        }
        if (
            markDraftAbandoned(
                draftId = followingDraftId,
                expectedRevisionNumber = expectedFollowingRevisionNumber,
                abandonedAtEpochMillis = mergedAtEpochMillis,
            ) != 1
        ) {
            throw ImmutablePayloadConflictException("problem_draft_head", followingDraftId)
        }
        return loadDraft(primaryDraftId)
    }

    @Transaction
    open suspend fun read(draftId: String): ProblemDraftRecord? =
        findDraftEntity(draftId)?.let { loadDraft(draftId) }

    open suspend fun readCanonicalSourceAsset(
        sourceAssetId: String,
    ): CanonicalSourceAssetRecord? = findSourceAsset(sourceAssetId)?.toRecord()

    @Transaction
    open suspend fun confirmTutorSession(
        command: ConfirmTutorSessionCommand,
    ): TutorSessionWriteResult {
        DatabaseContractValidator.validateConfirmTutorSession(command)
        val draft = findDraftEntity(command.draftId)
            ?: throw ImmutablePayloadConflictException("problem_draft", command.draftId)
        if (draft.origin != StudyDbValue.CaptureOrigin.TUTOR) {
            throw ImmutablePayloadConflictException("tutor_session_origin", command.draftId)
        }

        val existingById = findTutorSessionById(command.sessionId)
        val existingByDraft = findTutorSessionByDraft(command.draftId)
        if (existingById != null && existingByDraft != null && existingById != existingByDraft) {
            throw ImmutablePayloadConflictException("tutor_session", command.sessionId)
        }
        val existing = existingById ?: existingByDraft
        if (existing != null) {
            if (!existing.hasSameConfirmation(command)) {
                throw ImmutablePayloadConflictException("tutor_session", existing.sessionId)
            }
            return TutorSessionWriteResult(
                created = false,
                session = loadTutorSession(existing),
            )
        }

        if (
            draft.status != StudyDbValue.ProblemDraftStatus.EDITING ||
            draft.currentRevisionNumber != command.expectedRevisionNumber
        ) {
            throw ImmutablePayloadConflictException("problem_draft_head", command.draftId)
        }
        revise(
            ReviseProblemDraftCommand(
                draftId = command.draftId,
                expectedRevisionNumber = command.expectedRevisionNumber,
                revision = command.confirmedRevision,
            ),
        )
        val entity = TutorSessionEntity(
            sessionId = command.sessionId,
            draftId = command.draftId,
            draftRevisionNumber = command.confirmedRevision.revisionNumber,
            createdAtEpochMillis = command.createdAtEpochMillis,
        )
        if (insertTutorSession(entity) == -1L) {
            val winner = findTutorSessionByDraft(command.draftId)
                ?: findTutorSessionById(command.sessionId)
                ?: throw ImmutablePayloadConflictException("tutor_session", command.sessionId)
            if (!winner.hasSameConfirmation(command)) {
                throw ImmutablePayloadConflictException("tutor_session", winner.sessionId)
            }
            return TutorSessionWriteResult(created = false, session = loadTutorSession(winner))
        }
        return TutorSessionWriteResult(created = true, session = loadTutorSession(entity))
    }

    @Transaction
    open suspend fun readTutorSession(sessionId: String): TutorSessionRecord? {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        return findTutorSessionById(sessionId)?.let { loadTutorSession(it) }
    }

    @Transaction
    open suspend fun commitTutorSession(
        command: CommitTutorSessionCommand,
    ): CommitProblemDraftResult {
        DatabaseContractValidator.validateCommitTutorSession(command)
        val entity = findTutorSessionById(command.sessionId)
            ?: throw ImmutablePayloadConflictException("tutor_session", command.sessionId)
        val session = loadTutorSession(entity)
        if (
            command.commit.draftId != session.draftId ||
            command.commit.expectedRevisionNumber != session.draftRevisionNumber
        ) {
            throw ImmutablePayloadConflictException("tutor_session_revision", command.sessionId)
        }
        session.commitReceipt?.let { existing ->
            return CommitProblemDraftResult(created = false, receipt = existing)
        }
        return commitValidated(command.commit, allowTutorDraft = true)
    }

    @Transaction
    open suspend fun endTutorSession(
        command: EndTutorSessionCommand,
    ): EndTutorSessionResult {
        DatabaseContractValidator.validateEndTutorSession(command)
        val entity = findTutorSessionById(command.sessionId)
            ?: throw ImmutablePayloadConflictException("tutor_session", command.sessionId)
        val draft = findDraftEntity(entity.draftId)
            ?: throw ImmutablePayloadConflictException("problem_draft", entity.draftId)
        val receipt = findCommitReceiptByDraft(entity.draftId)
        return when (draft.status) {
            StudyDbValue.ProblemDraftStatus.EDITING -> {
                if (
                    receipt != null ||
                    draft.currentRevisionNumber != entity.draftRevisionNumber ||
                    markDraftAbandoned(
                        draftId = entity.draftId,
                        expectedRevisionNumber = entity.draftRevisionNumber,
                        abandonedAtEpochMillis = command.endedAtEpochMillis,
                    ) != 1
                ) {
                    throw ImmutablePayloadConflictException(
                        "tutor_session_end",
                        command.sessionId,
                    )
                }
                EndTutorSessionResult(
                    sessionId = command.sessionId,
                    draftId = entity.draftId,
                    endedAtEpochMillis = command.endedAtEpochMillis,
                    created = true,
                )
            }
            StudyDbValue.ProblemDraftStatus.ABANDONED -> {
                if (receipt != null || draft.currentRevisionNumber != entity.draftRevisionNumber) {
                    throw ImmutablePayloadConflictException(
                        "tutor_session_end",
                        command.sessionId,
                    )
                }
                EndTutorSessionResult(
                    sessionId = command.sessionId,
                    draftId = entity.draftId,
                    endedAtEpochMillis = draft.updatedAtEpochMillis,
                    created = false,
                )
            }
            StudyDbValue.ProblemDraftStatus.COMMITTED -> throw ImmutablePayloadConflictException(
                "tutor_session_already_saved",
                command.sessionId,
            )
            else -> throw ImmutablePayloadConflictException("problem_draft_status", entity.draftId)
        }
    }

    @Transaction
    open suspend fun commit(command: CommitProblemDraftCommand): CommitProblemDraftResult {
        DatabaseContractValidator.validateCommitProblemDraft(command)
        return commitValidated(command, allowTutorDraft = false)
    }

    @Transaction
    open suspend fun replayCommit(
        command: CommitProblemDraftCommand,
        allowTutorDraft: Boolean,
    ): CommitProblemDraftResult? {
        DatabaseContractValidator.validateCommitProblemDraft(command)
        val payloadFingerprint = commitPayloadFingerprint(command)
        val draft = findDraftEntity(command.draftId)
            ?: throw ImmutablePayloadConflictException("problem_draft", command.draftId)
        if (draft.origin == StudyDbValue.CaptureOrigin.TUTOR && !allowTutorDraft) {
            throw ImmutablePayloadConflictException("tutor_session_commit_boundary", command.draftId)
        }
        findCommitReceiptByCommand(command.commandId)?.let { existing ->
            if (existing.payloadFingerprint != payloadFingerprint) {
                throw ImmutablePayloadConflictException("problem_draft_commit", command.commandId)
            }
            return CommitProblemDraftResult(created = false, receipt = existing.toRecord())
        }
        findCommitReceiptByDraft(command.draftId)?.let {
            throw ImmutablePayloadConflictException("problem_draft_commit", command.draftId)
        }
        return null
    }

    private suspend fun commitValidated(
        command: CommitProblemDraftCommand,
        allowTutorDraft: Boolean,
    ): CommitProblemDraftResult {
        val payloadFingerprint = commitPayloadFingerprint(command)
        val draft = findDraftEntity(command.draftId)
            ?: throw ImmutablePayloadConflictException("problem_draft", command.draftId)
        if (draft.origin == StudyDbValue.CaptureOrigin.TUTOR && !allowTutorDraft) {
            throw ImmutablePayloadConflictException("tutor_session_commit_boundary", command.draftId)
        }
        findCommitReceiptByCommand(command.commandId)?.let { existing ->
            if (existing.payloadFingerprint != payloadFingerprint) {
                throw ImmutablePayloadConflictException("problem_draft_commit", command.commandId)
            }
            return CommitProblemDraftResult(created = false, receipt = existing.toRecord())
        }
        findCommitReceiptByDraft(command.draftId)?.let {
            throw ImmutablePayloadConflictException("problem_draft_commit", command.draftId)
        }

        if (
            draft.status != StudyDbValue.ProblemDraftStatus.EDITING ||
            draft.currentRevisionNumber != command.expectedRevisionNumber
        ) {
            throw ImmutablePayloadConflictException("problem_draft_head", command.draftId)
        }
        val draftRevision = findDraftRevision(command.draftId, command.expectedRevisionNumber)
            ?: throw ImmutablePayloadConflictException("problem_draft_revision", command.draftId)
        val captured = CapturedQuestionDocumentCodec.decode(draftRevision.questionDocumentSnapshot)
        if (CapturedQuestionDocumentValidator.validateForCommit(captured).isNotEmpty()) {
            throw ImmutablePayloadConflictException("unconfirmed_problem_draft", command.draftId)
        }
        val subject = draftRevision.subject
            ?: throw ImmutablePayloadConflictException("problem_draft_subject", command.draftId)
        val problemMarkdown = QuestionDocumentMarkdownProjection.project(captured.document)
        val committedIds = resolveCommittedProblemIds(
            command = command,
            draft = draft,
            draftRevision = draftRevision,
            subject = subject,
            problemMarkdown = problemMarkdown,
        )
        val draftSourceAssets = findDraftSourceAssets(command.draftId)
        command.problemOrganizationAuthorization?.let { grant ->
            require(grant.sourceDraftId == command.draftId) {
                "Problem organization authorization belongs to another draft"
            }
            require(grant.assets.size == draftSourceAssets.size) {
                "Problem organization authorization asset scope changed"
            }
            draftSourceAssets.forEach { source ->
                val authorized = grant.assets.singleOrNull {
                    it.assetId == source.sourceAssetId
                } ?: error("Problem organization source asset is outside authorization")
                val canonical = findSourceAsset(source.sourceAssetId)
                    ?: error("Problem organization source asset is missing")
                require(
                    authorized.sha256 == canonical.contentSha256 &&
                        authorized.byteSize == canonical.byteSize &&
                        authorized.width == canonical.width &&
                        authorized.height == canonical.height &&
                        authorized.selectedRegion == null,
                ) { "Problem organization source asset changed after authorization" }
            }
        }
        draftSourceAssets.forEach { source ->
            insertRevisionSourceAsset(
                ProblemRevisionSourceAssetEntity(
                    problemRevisionId = committedIds.problemRevisionId,
                    sourceAssetId = source.sourceAssetId,
                    role = "QUESTION_SOURCE",
                ),
            )
        }
        if (
            markDraftCommitted(
                draftId = command.draftId,
                expectedRevisionNumber = command.expectedRevisionNumber,
                committedAtEpochMillis = command.committedAtEpochMillis,
            ) != 1
        ) {
            throw ImmutablePayloadConflictException("problem_draft_head", command.draftId)
        }

        val receipt = ProblemDraftCommitReceiptEntity(
            commandId = command.commandId,
            payloadFingerprint = payloadFingerprint,
            draftId = command.draftId,
            draftRevisionNumber = command.expectedRevisionNumber,
            problemId = committedIds.problemId,
            problemRevisionId = committedIds.problemRevisionId,
            practiceUnitId = committedIds.practiceUnitId,
            errorBookEntryId = committedIds.errorBookEntryId,
            committedAtEpochMillis = command.committedAtEpochMillis,
        )
        insertCommitReceipt(receipt)
        insertProblemOrganizationWork(
            ProblemOrganizationWorkEntity(
                workId = "problem-organization-work:${receipt.commandId}",
                commitReceiptCommandId = receipt.commandId,
                status = StudyDbValue.ProblemOrganizationWorkStatus.WAITING_AUTHORIZATION,
                stateVersion = 0,
                attemptCount = 0,
                notBeforeEpochMillis = receipt.committedAtEpochMillis,
                requestId = null,
                requestSnapshot = null,
                authorizationGrantSnapshot = command.problemOrganizationAuthorization
                    ?.let(ProblemOrganizationAuthorizationGrantCodec::encode),
                leaseOwner = null,
                leaseExpiresAtEpochMillis = null,
                failureCode = null,
                failureMessage = null,
                createdAtEpochMillis = receipt.committedAtEpochMillis,
                updatedAtEpochMillis = receipt.committedAtEpochMillis,
            ),
        )
        return CommitProblemDraftResult(created = true, receipt = receipt.toRecord())
    }

    private suspend fun resolveCommittedProblemIds(
        command: CommitProblemDraftCommand,
        draft: ProblemDraftEntity,
        draftRevision: ProblemDraftRevisionEntity,
        subject: String,
        problemMarkdown: String,
    ): CommittedProblemIds {
        val canonicalFingerprint = stableSha256(subject, problemMarkdown)
        val exactExisting = findExactCommittedProblem(
            canonicalFingerprint = canonicalFingerprint,
            subject = subject,
            problemMarkdown = problemMarkdown,
        )
        return if (exactExisting == null) {
            createCommittedProblem(
                command = command,
                draft = draft,
                draftRevision = draftRevision,
                subject = subject,
                problemMarkdown = problemMarkdown,
                canonicalFingerprint = canonicalFingerprint,
            )
        } else {
            reuseCommittedProblem(command, exactExisting)
        }
    }

    private suspend fun createCommittedProblem(
        command: CommitProblemDraftCommand,
        draft: ProblemDraftEntity,
        draftRevision: ProblemDraftRevisionEntity,
        subject: String,
        problemMarkdown: String,
        canonicalFingerprint: String,
    ): CommittedProblemIds {
        insertProblem(
            ProblemEntity(
                problemId = command.problemId,
                canonicalFingerprint = canonicalFingerprint,
                subject = subject,
                createdAtEpochMillis = command.committedAtEpochMillis,
            ),
        )
        insertProblemRevision(
            ProblemRevisionEntity(
                revisionId = command.problemRevisionId,
                problemId = command.problemId,
                revisionNumber = 1,
                title = draftRevision.title,
                problemMarkdown = problemMarkdown,
                questionDocumentSnapshot = draftRevision.questionDocumentSnapshot,
                answerSpecId = null,
                answerSpecSnapshot = null,
                answerVerificationStatus = StudyDbValue.VerificationStatus.UNKNOWN,
                sourceType = "CAPTURE_CONFIRMED",
                sourceReference = draft.sourceAssetId,
                contentFingerprint = draftRevision.documentFingerprint,
                createdAtEpochMillis = command.committedAtEpochMillis,
            ),
        )
        insertPracticeUnit(
            PracticeUnitEntity(
                practiceUnitId = command.practiceUnitId,
                problemId = command.problemId,
                problemRevisionId = command.problemRevisionId,
                unitKey = "whole-problem",
                unitKind = "WHOLE_PROBLEM",
                title = draftRevision.title,
                promptMarkdown = problemMarkdown,
                estimatedSeconds = command.estimatedSeconds,
                createdAtEpochMillis = command.committedAtEpochMillis,
            ),
        )
        insertErrorBookEntry(
            ErrorBookEntryEntity(
                entryId = command.errorBookEntryId,
                practiceUnitId = command.practiceUnitId,
                problemId = command.problemId,
                currentRevisionId = command.problemRevisionId,
                sourceKey = "capture:${command.draftId}",
                status = StudyDbValue.ErrorBookStatus.ACTIVE,
                acceptedAtEpochMillis = command.committedAtEpochMillis,
                updatedAtEpochMillis = command.committedAtEpochMillis,
            ),
        )
        return CommittedProblemIds(
            problemId = command.problemId,
            problemRevisionId = command.problemRevisionId,
            practiceUnitId = command.practiceUnitId,
            errorBookEntryId = command.errorBookEntryId,
        )
    }

    private suspend fun reuseCommittedProblem(
        command: CommitProblemDraftCommand,
        existing: ExactCommittedProblemRow,
    ): CommittedProblemIds {
        val entryId = existing.errorBookEntryId ?: command.errorBookEntryId.also {
            insertErrorBookEntry(
                ErrorBookEntryEntity(
                    entryId = it,
                    practiceUnitId = existing.practiceUnitId,
                    problemId = existing.problemId,
                    currentRevisionId = existing.problemRevisionId,
                    sourceKey = "capture:${command.draftId}",
                    status = StudyDbValue.ErrorBookStatus.ACTIVE,
                    acceptedAtEpochMillis = command.committedAtEpochMillis,
                    updatedAtEpochMillis = command.committedAtEpochMillis,
                ),
            )
        }
        if (
            existing.errorBookEntryId != null &&
            reactivateErrorBookEntry(
                entryId = entryId,
                updatedAtEpochMillis = maxOf(
                    existing.entryUpdatedAtEpochMillis ?: 0L,
                    command.committedAtEpochMillis,
                ),
            ) != 1
        ) {
            throw ImmutablePayloadConflictException("error_book_entry", entryId)
        }
        return CommittedProblemIds(
            problemId = existing.problemId,
            problemRevisionId = existing.problemRevisionId,
            practiceUnitId = existing.practiceUnitId,
            errorBookEntryId = entryId,
        )
    }

    private suspend fun loadDraft(draftId: String): ProblemDraftRecord {
        val draft = checkNotNull(findDraftEntity(draftId))
        val sourceAssets = findDraftSourceAssets(draftId).map { binding ->
            ProblemDraftSourceAssetRecord(
                pageIndex = binding.pageIndex,
                sourceAsset = checkNotNull(findSourceAsset(binding.sourceAssetId)).toRecord(),
            )
        }
        val asset = checkNotNull(sourceAssets.firstOrNull()).sourceAsset
        val revision = checkNotNull(findDraftRevision(draftId, draft.currentRevisionNumber))
        return ProblemDraftRecord(
            draftId = draft.draftId,
            sourceAsset = asset,
            sourceAssets = sourceAssets,
            origin = draft.origin,
            status = draft.status,
            currentRevision = revision.toRecord(),
            createdAtEpochMillis = draft.createdAtEpochMillis,
            updatedAtEpochMillis = draft.updatedAtEpochMillis,
            requestFingerprint = draft.requestFingerprint,
        )
    }

    private companion object {
        const val MAX_CAPTURE_BUNDLE_PIXELS = 160_000_000L
    }

    private data class CommittedProblemIds(
        val problemId: String,
        val problemRevisionId: String,
        val practiceUnitId: String,
        val errorBookEntryId: String,
    )

    private suspend fun loadTutorSession(entity: TutorSessionEntity): TutorSessionRecord {
        val draft = findDraftEntity(entity.draftId)
            ?: throw ImmutablePayloadConflictException("problem_draft", entity.draftId)
        if (draft.origin != StudyDbValue.CaptureOrigin.TUTOR) {
            throw ImmutablePayloadConflictException("tutor_session_origin", entity.sessionId)
        }
        if (draft.currentRevisionNumber != entity.draftRevisionNumber) {
            throw ImmutablePayloadConflictException("tutor_session_revision", entity.sessionId)
        }
        val revision = findDraftRevision(entity.draftId, entity.draftRevisionNumber)
            ?: throw ImmutablePayloadConflictException("problem_draft_revision", entity.draftId)
        val revisionRecord = revision.toRecord()
        if (
            revision.author != StudyDbValue.ProblemDraftAuthor.USER ||
            revision.subject == null ||
            revision.documentFingerprint !=
                CapturedQuestionDocumentFingerprint.of(revisionRecord.questionDocument) ||
            revisionRecord.questionDocument.blockEvidence.any {
                it.sourceAssetId != draft.sourceAssetId
            } ||
            CapturedQuestionDocumentValidator.validateForCommit(
                revisionRecord.questionDocument,
            ).isNotEmpty()
        ) {
            throw ImmutablePayloadConflictException("tutor_session_confirmation", entity.sessionId)
        }
        val sourceAsset = findSourceAsset(draft.sourceAssetId)
            ?: throw ImmutablePayloadConflictException("canonical_source_asset", draft.sourceAssetId)
        val receipt = findCommitReceiptByDraft(entity.draftId)
        when (draft.status) {
            StudyDbValue.ProblemDraftStatus.EDITING -> if (receipt != null) {
                throw ImmutablePayloadConflictException("tutor_session_commit", entity.sessionId)
            }
            StudyDbValue.ProblemDraftStatus.COMMITTED -> if (
                receipt == null || receipt.draftRevisionNumber != entity.draftRevisionNumber
            ) {
                throw ImmutablePayloadConflictException("tutor_session_commit", entity.sessionId)
            }
            StudyDbValue.ProblemDraftStatus.ABANDONED -> if (receipt != null) {
                throw ImmutablePayloadConflictException("tutor_session_commit", entity.sessionId)
            }
            else -> throw ImmutablePayloadConflictException("problem_draft_status", entity.draftId)
        }
        return TutorSessionRecord(
            sessionId = entity.sessionId,
            draftId = entity.draftId,
            draftRevisionNumber = entity.draftRevisionNumber,
            createdAtEpochMillis = entity.createdAtEpochMillis,
            origin = draft.origin,
            draftStatus = draft.status,
            confirmedRevision = revisionRecord,
            sourceAsset = sourceAsset.toRecord(),
            commitReceipt = receipt?.toRecord(),
        )
    }

    private suspend fun TutorSessionEntity.hasSameConfirmation(
        command: ConfirmTutorSessionCommand,
    ): Boolean =
        draftId == command.draftId &&
            draftRevisionNumber == command.confirmedRevision.revisionNumber &&
            createdAtEpochMillis == command.createdAtEpochMillis &&
            findDraftRevision(draftId, draftRevisionNumber) == command.confirmedRevision.toEntity()

    private suspend fun replacementMatches(
        command: ReplaceProblemDraftCommand,
        existing: ProblemDraftEntity,
    ): Boolean = draftMatchesCreate(
        expected = command.replacement,
        existing = existing,
        expectedCreatedAtEpochMillis = command.replacedAtEpochMillis,
        requireEditingStatus = true,
    )

    private suspend fun draftMatchesCreate(
        expected: CreateProblemDraftCommand,
        existing: ProblemDraftEntity,
        expectedCreatedAtEpochMillis: Long,
        requireEditingStatus: Boolean = false,
    ): Boolean {
        val expectedAsset = expected.sourceAsset.toEntity()
        val expectedRevision = expected.initialRevision.toEntity()
        return existing.sourceAssetId == expectedAsset.sourceAssetId &&
            existing.origin == expected.origin &&
            existing.requestFingerprint == expected.requestFingerprint &&
            (!requireEditingStatus || existing.status == StudyDbValue.ProblemDraftStatus.EDITING) &&
            existing.createdAtEpochMillis == expectedCreatedAtEpochMillis &&
            findSourceAsset(expectedAsset.sourceAssetId)?.hasSameCanonicalContent(expectedAsset) == true &&
            findDraftRevision(existing.draftId, 1) == expectedRevision
    }
}

private fun CanonicalSourceAssetRecord.toEntity() = CanonicalSourceAssetEntity(
    sourceAssetId = sourceAssetId,
    contentSha256 = contentSha256,
    relativePath = relativePath,
    mimeType = mimeType,
    byteSize = byteSize,
    width = width,
    height = height,
    sourceType = sourceType,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun CanonicalSourceAssetEntity.toRecord() = CanonicalSourceAssetRecord(
    sourceAssetId = sourceAssetId,
    contentSha256 = contentSha256,
    relativePath = relativePath,
    mimeType = mimeType,
    byteSize = byteSize,
    width = width,
    height = height,
    sourceType = sourceType,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun CanonicalSourceAssetEntity.hasSameCanonicalContent(
    other: CanonicalSourceAssetEntity,
): Boolean = sourceAssetId == other.sourceAssetId &&
    contentSha256 == other.contentSha256 &&
    relativePath == other.relativePath &&
    mimeType == other.mimeType &&
    byteSize == other.byteSize &&
    width == other.width &&
    height == other.height

private fun ProblemDraftRevisionRecord.toEntity() = ProblemDraftRevisionEntity(
    draftId = draftId,
    revisionNumber = revisionNumber,
    basisRevisionNumber = basisRevisionNumber,
    subject = subject,
    title = title,
    questionDocumentSnapshot = CapturedQuestionDocumentCodec.encode(questionDocument),
    documentFingerprint = documentFingerprint,
    author = author,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun ProblemDraftRevisionEntity.toRecord() = ProblemDraftRevisionRecord(
    draftId = draftId,
    revisionNumber = revisionNumber,
    basisRevisionNumber = basisRevisionNumber,
    subject = subject,
    title = title,
    questionDocument = CapturedQuestionDocumentCodec.decode(questionDocumentSnapshot),
    documentFingerprint = documentFingerprint,
    author = author,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun ProblemDraftCommitReceiptEntity.toRecord() = ProblemDraftCommitReceipt(
    commandId = commandId,
    payloadFingerprint = payloadFingerprint,
    draftId = draftId,
    draftRevisionNumber = draftRevisionNumber,
    problemId = problemId,
    problemRevisionId = problemRevisionId,
    practiceUnitId = practiceUnitId,
    errorBookEntryId = errorBookEntryId,
    committedAtEpochMillis = committedAtEpochMillis,
)

private fun commitPayloadFingerprint(command: CommitProblemDraftCommand): String {
    val legacyFingerprint = stableSha256(
        command.commandId,
        command.draftId,
        command.expectedRevisionNumber.toString(),
        command.problemId,
        command.problemRevisionId,
        command.practiceUnitId,
        command.errorBookEntryId,
        command.estimatedSeconds.toString(),
        command.committedAtEpochMillis.toString(),
    )
    val authorization = command.problemOrganizationAuthorization ?: return legacyFingerprint
    return stableSha256(
        legacyFingerprint,
        "problem-organization-authorization",
        ProblemOrganizationAuthorizationGrantCodec.encode(authorization),
    )
}

private fun stableSha256(vararg fields: String): String {
    val canonical = buildString {
        fields.forEach { field -> append(field.length).append(':').append(field) }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
