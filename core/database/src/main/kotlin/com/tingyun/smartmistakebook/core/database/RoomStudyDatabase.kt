package com.tingyun.smartmistakebook.core.database

import androidx.paging.PagingSource
import androidx.room3.RoomRawQuery
import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.tingyun.smartmistakebook.core.database.dao.MistakeRow
import com.tingyun.smartmistakebook.core.database.dao.CanonicalSourceAssetRow
import com.tingyun.smartmistakebook.core.database.dao.KnowledgeGroundingSummaryRow
import com.tingyun.smartmistakebook.core.database.dao.LibraryFacetCountRow
import com.tingyun.smartmistakebook.core.database.dao.LibraryFtsSearchDao
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureHeadRow
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureIndexRow
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureSourceAssetRow
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureWorkspaceColumns
import com.tingyun.smartmistakebook.core.database.dao.ReviewPlanAggregate
import com.tingyun.smartmistakebook.core.database.dao.ReviewedKnowledgeCoverageRow
import com.tingyun.smartmistakebook.core.database.dao.activeSessionHead
import com.tingyun.smartmistakebook.core.database.dao.latestSessionHead
import com.tingyun.smartmistakebook.core.database.dao.toRecord
import com.tingyun.smartmistakebook.core.database.dao.toSnapshot
import com.tingyun.smartmistakebook.core.database.entity.AssessmentEventEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentItemSnapshotEntity
import com.tingyun.smartmistakebook.core.database.entity.ErrorBookEntryEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeMasteryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.PredictionOutcomeEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingRequestEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingResolutionEntity
import com.tingyun.smartmistakebook.core.database.entity.StudentModelPredictionEntity
import com.tingyun.smartmistakebook.core.database.entity.VisualInteractionAttemptEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeSourceBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSearchFeatureEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSourceEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialNodeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.LibraryCatalogView
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemMemoryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRevisionEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewPlanEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueItemEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueKnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueReasonEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionAdvanceReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionRevisionEntity
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import java.io.File
import com.tingyun.smartmistakebook.core.database.port.StudentModelPredictionRecord
import com.tingyun.smartmistakebook.core.database.port.ResolvedStudentModelPredictionRecord
import com.tingyun.smartmistakebook.core.database.port.VisualInteractionAttemptRecord
import com.tingyun.smartmistakebook.core.database.port.PracticeUnitKnowledgeBindingRecord

internal class RoomStudyDatabase(
    internal val database: StudyDatabase,
) : StudyDatabasePort {
    private val knowledgeResearchReviewStore = RoomKnowledgeResearchReviewStore(database)

    private val problemOrganization = RoomProblemOrganizationStore(database)
    private val batchImports = RoomBatchImportStore(database)
    override fun observeMistakes(): Flow<List<MistakeRecord>> =
        database.problemDao().observeActiveMistakes().map { rows -> rows.map(MistakeRow::toRecord) }

    override fun libraryPagingSource(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
    ): PagingSource<Int, LibraryCatalogRow> =
        MappingPagingSource(
            delegate = database.libraryQueryDao().pagingSource(
                searchText = searchText,
                subjectId = subjectId,
                sectionId = sectionId,
                knowledgePointId = knowledgePointId,
                masteryId = masteryId,
                sort = sort,
            ),
            transform = LibraryCatalogView::toRow,
        )

    override fun librarySearchPagingSource(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
        tokens: List<String>,
    ): PagingSource<Int, LibraryCatalogRow> {
        require(matchQuery.isNotBlank()) { "FTS search needs a non-blank MATCH expression" }
        require(tokens.isNotEmpty()) { "FTS search needs at least one query token" }
        val primaryPhrase = CjkTextTokenizer.quotedPhrase(tokens.first())
        val neverMatchPhrase = CjkTextTokenizer.quotedPhrase("\uFFFD")
        val extras = tokens.drop(1).take(3).map(CjkTextTokenizer::quotedPhrase)
        return RefreshingPagingSource(
            beforeLoad = ::refreshLibrarySearchProjection,
            delegate = MappingPagingSource(
                delegate = database.libraryFtsSearchDao().searchPagingSource(
                    buildLibrarySearchRawQuery(
                        matchQuery = matchQuery,
                        subjectId = subjectId,
                        sectionId = sectionId,
                        knowledgePointId = knowledgePointId,
                        masteryId = masteryId,
                        sort = sort,
                        primaryPhrase = primaryPhrase,
                        extraTokenPhrases = listOf(
                            extras.getOrElse(0) { neverMatchPhrase },
                            extras.getOrElse(1) { neverMatchPhrase },
                            extras.getOrElse(2) { neverMatchPhrase },
                        ),
                    ),
                ),
                transform = LibraryFtsSearchDao.LibrarySearchHitRow::toCatalogRow,
            ),
        )
    }

    /**
     * Builds the FTS4 search statement for [LibraryFtsSearchDao.searchPagingSource]
     * as a [RoomRawQuery].
     *
     * The weighted ranking sums per-column hit indicators expressed as
     * CASE WHEN EXISTS(...) constructs, which Room's @Query SQL parser
     * rejects; the statement therefore runs raw. Every dynamic value is
     * bound positionally through the binding function (never interpolated),
     * and the secondary sort term is chosen from a fixed whitelist, so no
     * caller-controlled text reaches the SQL.
     */
    private fun buildLibrarySearchRawQuery(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
        primaryPhrase: String,
        extraTokenPhrases: List<String>,
        limit: Int? = null,
        offset: Int? = null,
    ): RoomRawQuery {
        val bindings = mutableListOf<Any>(matchQuery)
        val filters = StringBuilder()
        if (subjectId != null) {
            filters.append("\n  AND catalog.subject = ?")
            bindings += subjectId
        }
        if (sectionId != null) {
            filters.append(
                "\n  AND EXISTS (\n" +
                    "      SELECT 1 FROM problem_classification_binding AS classification\n" +
                    "      WHERE classification.problem_id = catalog.problem_id\n" +
                    "        AND classification.basis_revision_id = catalog.problem_revision_id\n" +
                    "        AND classification.dimension = 'CHAPTER'\n" +
                    "        AND classification.label_id = ?\n" +
                    "  )",
            )
            bindings += sectionId
        }
        if (knowledgePointId != null) {
            filters.append(
                "\n  AND EXISTS (\n" +
                    "      SELECT 1 FROM problem_classification_binding AS classification\n" +
                    "      WHERE classification.problem_id = catalog.problem_id\n" +
                    "        AND classification.basis_revision_id = catalog.problem_revision_id\n" +
                    "        AND classification.dimension = 'KNOWLEDGE'\n" +
                    "        AND classification.label_id = ?\n" +
                    "  )",
            )
            bindings += knowledgePointId
        }
        if (masteryId != null) {
            filters.append("\n  AND catalog.mastery_id = ?")
            bindings += masteryId
        }
        val ranking = StringBuilder()
        listOf(
            "stem_text" to 4,
            "solution_text" to 3,
            "knowledge_points" to 2,
            "subject" to 2,
            "options_text" to 1,
            "chapter" to 1,
            "tags" to 1,
            "error_reason" to 1,
            "formula_tokens" to 1,
        ).forEachIndexed { index, (column, weight) ->
            if (index > 0) ranking.append("\n  + ")
            ranking.append(
                "$weight * (CASE WHEN EXISTS (\n" +
                    "    SELECT 1 FROM library_search_fts AS ranked\n" +
                    "    WHERE ranked.docid = content.content_row_id\n" +
                    "      AND ranked.$column MATCH ?\n" +
                    ") THEN 1 ELSE 0 END)",
            )
            bindings += primaryPhrase
        }
        extraTokenPhrases.forEach { phrase ->
            ranking.append(
                "\n  + (CASE WHEN EXISTS (\n" +
                    "    SELECT 1 FROM library_search_fts\n" +
                    "    WHERE library_search_fts.docid = content.content_row_id\n" +
                    "      AND library_search_fts MATCH ?\n" +
                    ") THEN 1 ELSE 0 END)",
            )
            bindings += phrase
        }
        val sortClause = when (sort) {
            "RECENTLY_CREATED" -> "catalog.created_at_epoch_millis DESC,\n    "
            "NEXT_REVIEW" -> "catalog.next_review_at_epoch_millis ASC,\n    "
            "LEAST_MASTERED" -> "catalog.retrievability ASC,\n    "
            else -> ""
        }
        if (limit != null) {
            bindings += limit.toLong()
            bindings += offset!!.toLong()
        }
        val sql = "SELECT catalog.*,\n" +
            "       snippet(library_search_fts, '【', '】', '…', -1, 12) AS snippet\n" +
            "FROM library_search_fts\n" +
            "JOIN library_search_content AS content\n" +
            "    ON content.content_row_id = library_search_fts.docid\n" +
            "JOIN library_catalog AS catalog\n" +
            "    ON catalog.problem_revision_id = content.problem_revision_id\n" +
            "WHERE library_search_fts MATCH ?$filters\n" +
            "ORDER BY (\n" +
            "    $ranking\n" +
            ") DESC,\n" +
            "    $sortClause" +
            "catalog.updated_at_epoch_millis DESC,\n" +
            "    catalog.entry_id ASC" +
            if (limit != null) "\nLIMIT ? OFFSET ?" else ""
        val orderedBindings = bindings.toList()
        return RoomRawQuery(sql) { statement ->
            orderedBindings.forEachIndexed { index, value ->
                when (value) {
                    is Int -> statement.bindLong(index + 1, value.toLong())
                    is Long -> statement.bindLong(index + 1, value)
                    else -> statement.bindText(index + 1, value as String)
                }
            }
        }
    }

    override suspend fun librarySearchCount(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
    ): Int {
        require(matchQuery.isNotBlank()) { "FTS search needs a non-blank MATCH expression" }
        refreshLibrarySearchProjection()
        return database.libraryFtsSearchDao().countSearch(
            matchQuery = matchQuery,
            subjectId = subjectId,
            sectionId = sectionId,
            knowledgePointId = knowledgePointId,
            masteryId = masteryId,
        )
    }

    override suspend fun librarySearchPage(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
        tokens: List<String>,
        offset: Int,
        limit: Int,
    ): List<LibraryCatalogRow> {
        require(matchQuery.isNotBlank()) { "FTS search needs a non-blank MATCH expression" }
        require(tokens.isNotEmpty()) { "FTS search needs at least one query token" }
        refreshLibrarySearchProjection()
        val primaryPhrase = CjkTextTokenizer.quotedPhrase(tokens.first())
        val neverMatchPhrase = CjkTextTokenizer.quotedPhrase("\uFFFD")
        val extras = tokens.drop(1).take(3).map(CjkTextTokenizer::quotedPhrase)
        return database.libraryFtsSearchDao().searchPage(
            buildLibrarySearchRawQuery(
                matchQuery = matchQuery,
                subjectId = subjectId,
                sectionId = sectionId,
                knowledgePointId = knowledgePointId,
                masteryId = masteryId,
                sort = sort,
                primaryPhrase = primaryPhrase,
                extraTokenPhrases = listOf(
                    extras.getOrElse(0) { neverMatchPhrase },
                    extras.getOrElse(1) { neverMatchPhrase },
                    extras.getOrElse(2) { neverMatchPhrase },
                ),
                limit = limit,
                offset = offset,
            ),
        ).map { it.toCatalogRow() }
    }

    override suspend fun librarySearchFacets(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        facet: String,
    ): List<LibraryFacetCountRecord> {
        require(matchQuery.isNotBlank()) { "FTS search needs a non-blank MATCH expression" }
        refreshLibrarySearchProjection()
        val dao = database.libraryFtsSearchDao()
        return when (facet) {
            "SUBJECT" -> dao.searchSubjectFacets(
                matchQuery = matchQuery,
                sectionId = sectionId,
                knowledgePointId = knowledgePointId,
                masteryId = masteryId,
            )
            "SECTION" -> dao.searchSectionFacets(
                matchQuery = matchQuery,
                subjectId = subjectId,
                knowledgePointId = knowledgePointId,
                masteryId = masteryId,
            )
            "KNOWLEDGE_POINT" -> dao.searchKnowledgeFacets(
                matchQuery = matchQuery,
                subjectId = subjectId,
                sectionId = sectionId,
                masteryId = masteryId,
            )
            "MASTERY" -> dao.searchMasteryFacets(
                matchQuery = matchQuery,
                subjectId = subjectId,
                sectionId = sectionId,
                knowledgePointId = knowledgePointId,
            )
            else -> error("Unsupported library facet kind: $facet")
        }.map(LibraryFacetCountRow::toRecord)
    }

    /**
     * Idempotently create FTS content-sync and outbox triggers on the raw
     * connection. Room rejects DDL in @Query, so this runs outside the DAO;
     * IF NOT EXISTS keeps repeat calls cheap.
     */
    private suspend fun ensureSearchTriggers() {
        database.useConnection(isReadOnly = false) { connection ->
        listOf(
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_search_fts_BEFORE_UPDATE " +
                "BEFORE UPDATE ON `library_search_content` BEGIN " +
                "DELETE FROM `library_search_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_search_fts_BEFORE_DELETE " +
                "BEFORE DELETE ON `library_search_content` BEGIN " +
                "DELETE FROM `library_search_fts` WHERE `docid`=OLD.`rowid`; END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_search_fts_AFTER_UPDATE " +
                "AFTER UPDATE ON `library_search_content` BEGIN " +
                "INSERT INTO `library_search_fts`(`docid`, `stem_text`, `options_text`, " +
                "`solution_text`, `subject`, `chapter`, `knowledge_points`, `tags`, " +
                "`error_reason`, `formula_tokens`) VALUES (NEW.`rowid`, NEW.`stem_text`, " +
                "NEW.`options_text`, NEW.`solution_text`, NEW.`subject`, NEW.`chapter`, " +
                "NEW.`knowledge_points`, NEW.`tags`, NEW.`error_reason`, " +
                "NEW.`formula_tokens`); END",
            "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_library_search_fts_AFTER_INSERT " +
                "AFTER INSERT ON `library_search_content` BEGIN " +
                "INSERT INTO `library_search_fts`(`docid`, `stem_text`, `options_text`, " +
                "`solution_text`, `subject`, `chapter`, `knowledge_points`, `tags`, " +
                "`error_reason`, `formula_tokens`) VALUES (NEW.`rowid`, NEW.`stem_text`, " +
                "NEW.`options_text`, NEW.`solution_text`, NEW.`subject`, NEW.`chapter`, " +
                "NEW.`knowledge_points`, NEW.`tags`, NEW.`error_reason`, " +
                "NEW.`formula_tokens`); END",
            "CREATE TRIGGER IF NOT EXISTS library_search_outbox_revision_insert " +
                "AFTER INSERT ON `problem_revision` BEGIN " +
                "INSERT INTO `library_search_outbox` (`revision_id`, `queued_at_epoch_millis`) " +
                "VALUES (NEW.`revision_id`, NEW.`created_at_epoch_millis`); END",
            "CREATE TRIGGER IF NOT EXISTS library_search_outbox_revision_update " +
                "AFTER UPDATE ON `problem_revision` BEGIN " +
                "INSERT INTO `library_search_outbox` (`revision_id`, `queued_at_epoch_millis`) " +
                "VALUES (NEW.`revision_id`, NEW.`created_at_epoch_millis`); END",
        ).forEach { sql ->
            connection.usePrepared(sql) { statement -> statement.step() }
        }
        }
    }

    override suspend fun refreshLibrarySearchProjection() {
        database.withWriteTransaction {
            val dao = database.libraryFtsSearchDao()
            ensureSearchTriggers()
            if (dao.countIndexed() == 0) {
                // First bootstrap (or repair): re-segment everything we know
                // about - the active library plus every already-materialized
                // row; stale content rows drop out through the projection read.
                val targets = (dao.readActiveLibraryRevisionIds() +
                    dao.readIndexedRevisionIds()).distinct()
                for (revisionId in targets) {
                    drainSearchRevision(dao, revisionId)
                }
            } else {
                // Incremental path: drain the outbox revision by revision;
                // each upsert/delete is mirrored into the FTS index by the
                // room_fts_content_sync triggers.
                while (true) {
                    val batch = dao.readOutboxBatch()
                    if (batch.isEmpty()) break
                    for (revisionId in batch.distinct()) {
                        drainSearchRevision(dao, revisionId)
                    }
                }
            }
        }
    }

    private suspend fun drainSearchRevision(
        dao: LibraryFtsSearchDao,
        revisionId: String,
    ) {
        val row = dao.readRevisionForProjection(revisionId)
        if (row == null) {
            // Dropped from the library: the BEFORE_DELETE sync trigger
            // removes the FTS row alongside the content row.
            dao.deleteContent(revisionId)
        } else {
            val stem = CjkTextTokenizer.segment(row.title + "\n" + row.problemMarkdown)
            val options = CjkTextTokenizer.segment(row.questionDocumentSnapshot.orEmpty())
            val solution = CjkTextTokenizer.segment(row.answerSpecSnapshot.orEmpty())
            val subject = CjkTextTokenizer.segment(row.subject)
            val chapter = CjkTextTokenizer.segment(row.chapter)
            val knowledge = CjkTextTokenizer.segment(row.knowledgePoints)
            val tags = CjkTextTokenizer.segment(row.tags)
            val errorReason = CjkTextTokenizer.segment(row.errorReason)
            val formulaTokens = FormulaSearchProjection.tokensForSnapshot(
                row.questionDocumentSnapshot,
            )
            if (dao.countContentFor(revisionId) > 0) {
                dao.updateContent(
                    revisionId = revisionId,
                    stemText = stem,
                    optionsText = options,
                    solutionText = solution,
                    subject = subject,
                    chapter = chapter,
                    knowledgePoints = knowledge,
                    tags = tags,
                    errorReason = errorReason,
                    formulaTokens = formulaTokens,
                )
            } else {
                dao.insertContent(
                    revisionId = revisionId,
                    stemText = stem,
                    optionsText = options,
                    solutionText = solution,
                    subject = subject,
                    chapter = chapter,
                    knowledgePoints = knowledge,
                    tags = tags,
                    errorReason = errorReason,
                    formulaTokens = formulaTokens,
                )
            }
        }
        dao.clearOutboxFor(revisionId)
    }

    override suspend fun libraryCatalogPage(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
        offset: Int,
        limit: Int,
    ): List<LibraryCatalogRow> {
        return database.libraryQueryDao()
            .page(
                searchText = searchText,
                subjectId = subjectId,
                sectionId = sectionId,
                knowledgePointId = knowledgePointId,
                masteryId = masteryId,
                sort = sort,
                offset = offset,
                limit = limit,
            )
            .map(LibraryCatalogView::toRow)
    }

    override suspend fun libraryCatalogCount(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
    ): Int {
        return database.libraryQueryDao().count(
            searchText = searchText,
            subjectId = subjectId,
            sectionId = sectionId,
            knowledgePointId = knowledgePointId,
            masteryId = masteryId,
        )
    }

    override suspend fun libraryCatalogFacets(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        facet: String,
    ): List<LibraryFacetCountRecord> {
        return when (facet) {
            "SUBJECT" -> database.libraryQueryDao().subjectFacets(
                searchText = searchText,
                sectionId = sectionId,
                knowledgePointId = knowledgePointId,
                masteryId = masteryId,
            ).map(LibraryFacetCountRow::toRecord)

            "SECTION" -> database.libraryQueryDao().sectionFacets(
                searchText = searchText,
                subjectId = subjectId,
                knowledgePointId = knowledgePointId,
                masteryId = masteryId,
            ).map(LibraryFacetCountRow::toRecord)

            "KNOWLEDGE_POINT" -> database.libraryQueryDao().knowledgeFacets(
                searchText = searchText,
                subjectId = subjectId,
                sectionId = sectionId,
                masteryId = masteryId,
            ).map(LibraryFacetCountRow::toRecord)

            "MASTERY" -> database.libraryQueryDao().masteryFacets(
                searchText = searchText,
                subjectId = subjectId,
                sectionId = sectionId,
                knowledgePointId = knowledgePointId,
            ).map(LibraryFacetCountRow::toRecord)

            else -> error("Unsupported library facet kind: $facet")
        }
    }

    override fun observeLearningLedgerHead(learnerId: String): Flow<Long> {
        require(learnerId.isNotBlank())
        return database.learningDao().observeLedgerHead(learnerId)
    }

    override fun observePendingProblemDraftCount(): Flow<Int> =
        database.problemDraftTransactionDao().observePendingDraftCount()

    override fun observeTutorTurnResponses(sessionId: String): Flow<List<TutorTurnResponseRecord>> {
        require(sessionId.isNotBlank())
        return database.tutorInteractionDao().observe(sessionId)
    }

    override fun observeRecentTutorConversations(
        limit: Int,
    ): Flow<List<TutorConversationRecord>> {
        require(limit > 0) { "Tutor conversation limit must be positive" }
        return database.tutorConversationDao().observeRecent(limit)
    }

    override fun observeTutorMessages(
        conversationId: String,
    ): Flow<List<TutorMessageRecord>> {
        require(conversationId.isNotBlank()) { "Tutor conversation id must not be blank" }
        return database.tutorConversationDao().observeMessages(conversationId)
    }

    override fun observeTutorConversation(
        conversationId: String,
    ): Flow<TutorConversationRecord?> {
        require(conversationId.isNotBlank()) { "Tutor conversation id must not be blank" }
        return database.tutorConversationDao().observeConversation(conversationId)
    }

    override fun observePendingCaptureDrafts(): Flow<List<PendingCaptureDraftRecord>> =
        database.invalidationTracker.createFlow(*PENDING_CAPTURE_TABLES).mapLatest {
            loadPendingCaptureBatch()
        }

    override fun observeBatchImportJobs(): Flow<List<BatchImportJobRecord>> = batchImports.observe()

    override fun observeReviewPlan(reviewPlanId: String): Flow<ReviewPlanBundle?> =
        database.reviewDao().observePlan(reviewPlanId).map { it?.toRecord() }

    override fun observeReviewPlanForSession(sessionId: String): Flow<ReviewPlanBundle?> =
        database.reviewDao().observePlanForSession(sessionId).map { it?.toRecord() }

    override fun observeActiveReviewPlan(learnerId: String): Flow<ReviewPlanBundle?> =
        database.reviewDao().observeActivePlans(learnerId).map { activePlans ->
            when (activePlans.size) {
                0 -> null
                1 -> activePlans.single().toRecord()
                else -> throw ImmutablePayloadConflictException(
                    "active_review_session",
                    learnerId,
                )
            }
        }

    override fun observeCurrentReviewPlan(
        learnerId: String,
        localDayEpochDay: Long,
        timeZoneId: String,
    ): Flow<ReviewPlanBundle?> = database.reviewDao()
        .observeCurrentPlan(learnerId, localDayEpochDay, timeZoneId)
        .map { it?.toRecord() }

    override fun observeCompletedReviewLocalDays(
        learnerId: String,
        limit: Int,
    ): Flow<List<Long>> {
        require(learnerId.isNotBlank())
        require(limit in 1..MAX_REVIEW_COMPLETION_HISTORY_DAYS)
        return database.reviewDao().observeCompletedLocalDays(learnerId, limit)
    }

    override suspend fun countMistakes(): Int = database.problemDao().countActiveMistakes()

    override suspend fun checkpointForBackup() {
        // WAL checkpoint so committed rows are captured before the archived
        // database file is packaged. This stays the cheap flush; the archive
        // flow itself uses snapshotForBackup below for the consistency
        // snapshot.
        database.useConnection(isReadOnly = false) { connection ->
            connection.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { statement ->
                while (statement.step()) {
                    // Checkpoint result row is intentionally consumed and ignored.
                }
            }
        }
    }

    override suspend fun snapshotForBackup(
        sourceDatabaseFile: File,
        snapshotTarget: File,
    ) {
        // Consistency snapshot for backup archives (see BackupPort docs).
        //
        // Preferred: VACUUM INTO (SQLite >= 3.27.0) writes a complete,
        // transaction-consistent copy of the database into the target file
        // even while concurrent writers are active; this matches the
        // semantics required by the SQLite Online Backup API approach.
        //
        // Fallback (older SQLite builds): TRUNCATE WAL checkpoint plus a
        // read-only byte copy of the main file, which is consistent as long
        // as no writer commits during the copy. The backup flow guarantees
        // that by running while the app is otherwise idle.
        if (snapshotTarget.exists() && !snapshotTarget.delete()) {
            error("Cannot replace existing backup snapshot target")
        }
        val sqliteVersion = readSqliteVersion()
        if (sqliteVersion != null && isAtLeast(sqliteVersion, 3, 27, 0)) {
            val escapedPath = snapshotTarget.absolutePath.replace("'", "''")
            val succeeded = try {
                database.useConnection(isReadOnly = false) { connection ->
                    connection.usePrepared("VACUUM INTO '$escapedPath'") { statement ->
                        while (statement.step()) {
                            // VACUUM INTO produces no result rows.
                        }
                    }
                }
                snapshotTarget.isFile && snapshotTarget.length() > 0L
            } catch (_: Exception) {
                // Older SQLite builds reject the INTO clause; fall through to
                // the checkpoint + read-only copy fallback.
                false
            }
            if (succeeded) {
                fsyncSnapshot(snapshotTarget)
                return
            }
            if (snapshotTarget.exists()) snapshotTarget.delete()
        }
        // Fallback: checkpoint every WAL frame into the main file, then copy
        // the main file through a plain read-only stream.
        checkpointForBackup()
        sourceDatabaseFile.inputStream().use { input ->
            snapshotTarget.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        fsyncSnapshot(snapshotTarget)
    }

    private suspend fun readSqliteVersion(): String? {
        val holder = arrayOfNulls<String>(1)
        database.useConnection(isReadOnly = true) { connection ->
            connection.usePrepared("SELECT sqlite_version()") { statement ->
                if (statement.step()) {
                    holder[0] = statement.getText(0)
                }
            }
        }
        return holder[0]
    }

    private fun isAtLeast(version: String, major: Int, minor: Int, patch: Int): Boolean {
        val parts = version.split('.').mapNotNull { part -> part.toIntOrNull() }
        val actual = listOf(
            parts.getOrElse(0) { 0 },
            parts.getOrElse(1) { 0 },
            parts.getOrElse(2) { 0 },
        )
        val required = listOf(major, minor, patch)
        for (index in 0..2) {
            if (actual[index] != required[index]) return actual[index] > required[index]
        }
        return true
    }

    private fun fsyncSnapshot(file: File) {
        runCatching {
            java.io.RandomAccessFile(file, "r").use { raf -> raf.fd.sync() }
        }
    }

    override suspend fun clearAllData() {
        database.useConnection(isReadOnly = false) { connection ->
            connection.usePrepared("PRAGMA foreign_keys = OFF") { statement ->
                while (statement.step()) {
                    // PRAGMA result row is intentionally ignored.
                }
            }
            val tableNames = buildList {
                connection.usePrepared(
                    "SELECT name FROM sqlite_master WHERE type = 'table' " +
                        "AND name NOT IN ('room_master_table', 'android_metadata')",
                ) { statement ->
                    while (statement.step()) {
                        add(requireNotNull(statement.getText(0)))
                    }
                }
            }
            tableNames.forEach { table ->
                connection.usePrepared("DELETE FROM `$table`") { statement ->
                    statement.step()
                }
            }
            connection.usePrepared("PRAGMA foreign_keys = ON") { statement ->
                while (statement.step()) {
                    // PRAGMA result row is intentionally ignored.
                }
            }
        }
    }

    override suspend fun recordStudentModelPredictions(
        predictions: List<StudentModelPredictionRecord>,
    ) {
        if (predictions.isEmpty()) return
        database.predictionAuditDao().insertPredictions(
            predictions.map { record ->
                StudentModelPredictionEntity(
                    predictionId = record.predictionId,
                    modelId = record.modelId,
                    modelVersion = record.modelVersion,
                    algorithmHash = record.algorithmHash,
                    practiceUnitId = record.practiceUnitId,
                    knowledgeNodeId = record.knowledgeNodeId,
                    featureFingerprint = record.featureFingerprint,
                    predictedScore = record.predictedScore,
                    conservativeScore = record.conservativeScore,
                    predictionWindowStartEpochMillis = record.predictionWindowStartEpochMillis,
                    predictionWindowEndEpochMillis = record.predictionWindowEndEpochMillis,
                    predictedAtEpochMillis = record.predictedAtEpochMillis,
                )
            },
        )
    }

    override suspend fun resolveStudentModelPredictions(
        practiceUnitId: String,
        wasIndependentCorrect: Boolean,
        observedAtEpochMillis: Long,
        responseLatencyMs: Long?,
        hintCount: Int,
    ): Int {
        val auditDao = database.predictionAuditDao()
        val pending = auditDao
            .findPendingForPracticeUnit(practiceUnitId, observedAtEpochMillis)
        pending.forEach { prediction ->
            auditDao.upsertOutcome(
                PredictionOutcomeEntity(
                    predictionId = prediction.predictionId,
                    observedAtEpochMillis = observedAtEpochMillis,
                    wasIndependentCorrect = wasIndependentCorrect,
                    responseLatencyMs = responseLatencyMs,
                    hintCount = hintCount,
                ),
            )
            auditDao.markResolved(prediction.predictionId)
        }
        return pending.size
    }

    override suspend fun readResolvedStudentModelPredictions(
        modelId: String,
        modelVersion: String,
    ): List<ResolvedStudentModelPredictionRecord> =
        database.predictionAuditDao()
            .findResolvedForModel(modelId, modelVersion)
            .map { row ->
                ResolvedStudentModelPredictionRecord(
                    predictionId = row.predictionId,
                    modelId = row.modelId,
                    modelVersion = row.modelVersion,
                    algorithmHash = row.algorithmHash,
                    predictedScore = row.predictedScore,
                    conservativeScore = row.conservativeScore,
                    wasIndependentCorrect = row.wasIndependentCorrect,
                    observedAtEpochMillis = row.observedAtEpochMillis,
                )
            }

    override suspend fun findLastPredictionLatencyMs(practiceUnitId: String): Long? =
        database.predictionAuditDao().findLastLatencyMs(practiceUnitId)

    override suspend fun recordVisualInteractionAttempt(
        attempt: VisualInteractionAttemptRecord,
    ) {
        database.visualInteractionAttemptDao().insertAttempt(
            VisualInteractionAttemptEntity(
                attemptId = attempt.attemptId,
                problemRevisionId = attempt.problemRevisionId,
                actionKind = attempt.actionKind,
                actionPayload = attempt.actionPayload,
                feasible = attempt.feasible,
                feedback = attempt.feedback,
                attemptedAtEpochMillis = attempt.attemptedAtEpochMillis,
            ),
        )
    }

    override suspend fun readVisualInteractionAttempts(
        problemRevisionId: String,
    ): List<VisualInteractionAttemptRecord> =
        database.visualInteractionAttemptDao()
            .findForProblemRevision(problemRevisionId)
            .map { row ->
                VisualInteractionAttemptRecord(
                    attemptId = row.attemptId,
                    problemRevisionId = row.problemRevisionId,
                    actionKind = row.actionKind,
                    actionPayload = row.actionPayload,
                    feasible = row.feasible,
                    feedback = row.feedback,
                    attemptedAtEpochMillis = row.attemptedAtEpochMillis,
                )
            }

    override suspend fun readPracticeUnitKnowledgeBindings(
        practiceUnitId: String,
    ): List<PracticeUnitKnowledgeBindingRecord> =
        database.problemOrganizationDao()
            .readKnowledgeBindingsForPracticeUnit(practiceUnitId)
            .map { row ->
                PracticeUnitKnowledgeBindingRecord(
                    bindingId = row.bindingId,
                    practiceUnitId = row.practiceUnitId,
                    knowledgeNodeId = row.knowledgeNodeId,
                    basisRevisionId = row.basisRevisionId,
                    taxonomyVersion = row.taxonomyVersion,
                    acceptedAtEpochMillis = row.acceptedAtEpochMillis,
                )
            }

    override suspend fun readSubjectKnowledgeNodes(
        subject: String,
        limit: Int,
    ): List<KnowledgeNodeSeedRecord> {
        require(subject.isNotBlank()) { "subject must not be blank" }
        require(limit in 1..256) { "knowledge-node limit is outside the supported range" }
        return database.problemOrganizationDao().readSubjectKnowledgeNodes(subject, limit).map {
            it.toSeedRecord()
        }
    }

    override suspend fun readSubjectKnowledgeRecallCandidates(
        subject: String,
        searchFeatures: Set<String>,
        limit: Int,
    ): List<KnowledgeNodeSeedRecord> {
        require(subject.isNotBlank()) { "subject must not be blank" }
        require(limit in 1..MAX_KNOWLEDGE_RECALL_CANDIDATES) {
            "knowledge recall candidate limit is outside the supported range"
        }
        require(searchFeatures.size <= KnowledgeSearchFeatureExtractor.MAX_QUERY_FEATURES) {
            "knowledge recall query has too many search features"
        }
        if (searchFeatures.isEmpty()) {
            return readSubjectKnowledgeNodes(subject, limit.coerceAtMost(256))
        }
        ensureKnowledgeSearchIndex(subject)
        val dao = database.problemOrganizationDao()
        val matched = dao.searchSubjectKnowledgeRecallCandidates(subject, searchFeatures, limit)
        val matchedIds = matched.mapTo(hashSetOf(), KnowledgeNodeEntity::knowledgeNodeId)
        val parents = dao.readKnowledgeNodesByIds(
            matched.mapNotNullTo(hashSetOf(), KnowledgeNodeEntity::parentKnowledgeNodeId) - matchedIds,
        )
        return (parents + matched)
            .distinctBy(KnowledgeNodeEntity::knowledgeNodeId)
            .map(KnowledgeNodeEntity::toSeedRecord)
    }

    private suspend fun ensureKnowledgeSearchIndex(subject: String) {
        val dao = database.problemOrganizationDao()
        val reviewedCount = dao.countReviewedKnowledgeNodesBySubject(subject)
        if (reviewedCount == 0 || dao.countIndexedKnowledgeNodesBySubject(subject) >= reviewedCount) return
        database.withWriteTransaction {
            val missingCheckCount = dao.countReviewedKnowledgeNodesBySubject(subject)
            if (dao.countIndexedKnowledgeNodesBySubject(subject) < missingCheckCount) {
                val nodes = dao.readSubjectKnowledgeRecallCandidates(subject, missingCheckCount)
                dao.insertKnowledgeSearchFeatures(nodes.flatMap(KnowledgeNodeEntity::toSearchFeatures))
            }
        }
    }

    override suspend fun readKnowledgeNodesByIds(ids: Set<String>): List<KnowledgeNodeSeedRecord> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(KNOWLEDGE_NODE_QUERY_CHUNK_SIZE)
            .flatMap { chunk ->
                database.problemOrganizationDao().readKnowledgeNodesByIds(chunk.toSet())
            }
            .map(KnowledgeNodeEntity::toSeedRecord)
    }

    override suspend fun readKnowledgeSourcesByIds(ids: Set<String>): List<KnowledgeSourceSeedRecord> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(KNOWLEDGE_NODE_QUERY_CHUNK_SIZE)
            .flatMap { chunk ->
                database.problemOrganizationDao().readKnowledgeSourcesByIds(chunk.toSet())
            }
            .map(KnowledgeSourceEntity::toSeedRecord)
    }

    override suspend fun readSubjectKnowledgeNodeRelations(
        subject: String,
        limit: Int,
    ): List<KnowledgeNodeRelationRecord> {
        require(subject.isNotBlank()) { "subject must not be blank" }
        require(limit in 1..16_384) { "knowledge relation limit is outside the supported range" }
        return database.knowledgeNodeRelationDao().readBySubject(subject, limit)
            .map(KnowledgeNodeRelationEntity::toRecord)
    }

    override suspend fun readKnowledgeNodeRelationsForDependents(
        subject: String,
        dependentKnowledgeNodeIds: Set<String>,
    ): List<KnowledgeNodeRelationRecord> {
        require(subject.isNotBlank()) { "subject must not be blank" }
        require(dependentKnowledgeNodeIds.size <= 256) {
            "too many dependent knowledge nodes were requested"
        }
        if (dependentKnowledgeNodeIds.isEmpty()) return emptyList()
        return database.knowledgeNodeRelationDao()
            .readForDependents(subject, dependentKnowledgeNodeIds)
            .map(KnowledgeNodeRelationEntity::toRecord)
    }

    override suspend fun readKnowledgeTeachingMaterialsForNodes(
        subject: String,
        knowledgeNodeIds: Set<String>,
        limit: Int,
    ): List<KnowledgeTeachingMaterialRecord> {
        require(subject.isNotBlank()) { "subject must not be blank" }
        require(knowledgeNodeIds.size <= 256) {
            "too many knowledge nodes were requested for teaching context"
        }
        require(limit in 1..64) { "teaching-material limit is outside the supported range" }
        if (knowledgeNodeIds.isEmpty()) return emptyList()
        return database.knowledgeTeachingMaterialDao()
            .readForKnowledgeNodes(subject, knowledgeNodeIds, limit)
            .map(KnowledgeTeachingMaterialEntity::toRecord)
    }

    override suspend fun readKnowledgeTeachingMaterialsByIds(
        materialIds: Set<String>,
    ): List<KnowledgeTeachingMaterialRecord> {
        if (materialIds.isEmpty()) return emptyList()
        return materialIds.chunked(KNOWLEDGE_NODE_QUERY_CHUNK_SIZE)
            .flatMap { ids ->
                database.knowledgeTeachingMaterialDao().readByIds(ids.toSet())
            }
            .map(KnowledgeTeachingMaterialEntity::toRecord)
    }

    override suspend fun readKnowledgeTeachingMaterialNodeBindings(
        materialIds: Set<String>,
    ): List<KnowledgeTeachingMaterialNodeBindingRecord> {
        if (materialIds.isEmpty()) return emptyList()
        return materialIds.chunked(KNOWLEDGE_NODE_QUERY_CHUNK_SIZE)
            .flatMap { ids ->
                database.knowledgeTeachingMaterialDao().readBindingsForMaterials(ids.toSet())
            }
            .map(KnowledgeTeachingMaterialNodeBindingEntity::toRecord)
    }

    override suspend fun importKnowledgeNodeRelations(relations: List<KnowledgeNodeRelationRecord>) {
        if (relations.isEmpty()) return
        database.withWriteTransaction {
            val nodeIds = relations.flatMapTo(mutableSetOf()) {
                listOf(it.prerequisiteKnowledgeNodeId, it.dependentKnowledgeNodeId)
            }
            val sourceIds = relations.mapTo(mutableSetOf(), KnowledgeNodeRelationRecord::sourceId)
            val subjects = relations.mapTo(mutableSetOf(), KnowledgeNodeRelationRecord::subject)
            val existingRelations = subjects.flatMap { subject ->
                require(database.knowledgeNodeRelationDao().countBySubject(subject) <= 16_384) {
                    "knowledge relation graph exceeds the supported validation budget"
                }
                database.knowledgeNodeRelationDao().readBySubject(subject, 16_384)
            }.map(KnowledgeNodeRelationEntity::toRecord)
            KnowledgeNodeRelationContract.validate(
                incoming = relations,
                nodes = readKnowledgeNodesByIds(nodeIds),
                sources = readKnowledgeSourcesByIds(sourceIds),
                existing = existingRelations,
            )
            database.knowledgeNodeRelationDao().importAll(
                relations.map(KnowledgeNodeRelationRecord::toEntity),
            )
        }
    }

    override suspend fun importKnowledgeTeachingMaterials(
        materials: List<KnowledgeTeachingMaterialRecord>,
        bindings: List<KnowledgeTeachingMaterialNodeBindingRecord>,
        sources: List<KnowledgeSourceSeedRecord>,
    ) {
        if (materials.isEmpty() && bindings.isEmpty() && sources.isEmpty()) return
        database.withWriteTransaction {
            KnowledgeBaseImportContract.validateSourcesOnly(sources)
            val nodes = readKnowledgeNodesByIds(
                bindings.mapTo(hashSetOf(), KnowledgeTeachingMaterialNodeBindingRecord::knowledgeNodeId),
            )
            val requiredSourceIds = materials.mapTo(hashSetOf(), KnowledgeTeachingMaterialRecord::sourceId)
                .apply {
                    addAll(sources.map(KnowledgeSourceSeedRecord::sourceId))
                }
            val existingSources = readKnowledgeSourcesByIds(requiredSourceIds)
            val existingSourcesById = existingSources.associateBy(KnowledgeSourceSeedRecord::sourceId)
            sources.forEach { source ->
                existingSourcesById[source.sourceId]?.let { existing ->
                    if (existing != source) {
                        throw ImmutablePayloadConflictException(
                            entityType = "knowledgeSource",
                            entityId = source.sourceId,
                        )
                    }
                }
            }
            val sourcesToInsert = sources.filterNot { source ->
                existingSourcesById.containsKey(source.sourceId)
            }
            val validatedSources = (
                existingSources + sourcesToInsert
                ).distinctBy(KnowledgeSourceSeedRecord::sourceId)
            KnowledgeTeachingMaterialContract.validate(
                materials = materials,
                bindings = bindings,
                nodes = nodes,
                sources = validatedSources,
            )
            if (sourcesToInsert.isNotEmpty()) {
                database.problemOrganizationDao().insertKnowledgeSources(
                    sourcesToInsert.map(KnowledgeSourceSeedRecord::toEntity),
                )
            }
            database.knowledgeTeachingMaterialDao().importAll(
                materials = materials.map(KnowledgeTeachingMaterialRecord::toEntity),
                bindings = bindings.map(KnowledgeTeachingMaterialNodeBindingRecord::toEntity),
            )
        }
    }

    override suspend fun readKnowledgeNodeSourceBindings(
        knowledgeNodeIds: Set<String>,
    ): List<KnowledgeNodeSourceBindingSeedRecord> {
        if (knowledgeNodeIds.isEmpty()) return emptyList()
        return knowledgeNodeIds.chunked(KNOWLEDGE_NODE_QUERY_CHUNK_SIZE)
            .flatMap { chunk ->
                database.problemOrganizationDao().readKnowledgeNodeSourceBindings(chunk.toSet())
            }
            .map(KnowledgeNodeSourceBindingEntity::toSeedRecord)
    }

    override suspend fun importKnowledgeBase(
        sources: List<KnowledgeSourceSeedRecord>,
        nodes: List<KnowledgeNodeSeedRecord>,
        bindings: List<KnowledgeNodeSourceBindingSeedRecord>,
    ) {
        val dependencies = readKnowledgeBaseDependencies(sources, nodes, bindings)
        KnowledgeBaseImportContract.validate(
            sources = sources,
            nodes = nodes,
            bindings = bindings,
            existingSources = dependencies.sources,
            existingParentNodes = dependencies.parentNodes,
        )
        database.problemOrganizationDao().importKnowledgeBase(
            sources = sources.map(KnowledgeSourceSeedRecord::toEntity),
            nodes = nodes.map(KnowledgeNodeSeedRecord::toEntity),
            bindings = bindings.map(KnowledgeNodeSourceBindingSeedRecord::toEntity),
            searchFeatures = nodes.flatMap(KnowledgeNodeSeedRecord::toSearchFeatures),
        )
    }

    override suspend fun applyReviewedKnowledgePack(
        command: ApplyReviewedKnowledgePackCommand,
    ): List<KnowledgeGroundingResolutionRecord> = database.withWriteTransaction {
        applyReviewedKnowledgePackInTransaction(command)
    }

    override suspend fun applyApprovedKnowledgeResearchPack(
        command: ApplyApprovedKnowledgeResearchPackCommand,
    ): List<KnowledgeGroundingResolutionRecord> = database.withWriteTransaction {
        val review = knowledgeResearchReviewStore.read(command.reviewBundleId)
            ?: throw DatabaseContractViolationException(
                "Knowledge research review bundle does not exist",
            )
        val packFingerprint = ApprovedKnowledgeResearchPackContract.validate(review, command)
        if (review.status == StudyDbValue.KnowledgeResearchReviewStatus.APPLIED) {
            if (
                review.appliedPackFingerprint != packFingerprint ||
                review.appliedAtEpochMillis != command.appliedAtEpochMillis
            ) {
                throw ImmutablePayloadConflictException(
                    entityType = "approved knowledge research pack",
                    entityId = command.reviewBundleId,
                )
            }
            return@withWriteTransaction readAppliedKnowledgeResearchResolutions(command)
        }
        val resolutions = applyReviewedKnowledgePackInTransaction(command.pack)
        knowledgeResearchReviewStore.markApplied(
            bundleId = command.reviewBundleId,
            packFingerprint = packFingerprint,
            appliedAtEpochMillis = command.appliedAtEpochMillis,
        )
        resolutions
    }

    private suspend fun readAppliedKnowledgeResearchResolutions(
        command: ApplyApprovedKnowledgeResearchPackCommand,
    ): List<KnowledgeGroundingResolutionRecord> {
        val groundingKeys = command.pack.resolutions
            .mapTo(mutableSetOf(), ResolveKnowledgeGroundingCommand::groundingKey)
        val byGroundingKey = database.knowledgeGroundingDao().readResolutions(groundingKeys)
            .associateBy(KnowledgeGroundingResolutionEntity::groundingKey)
        return command.pack.resolutions.map { expected ->
            byGroundingKey[expected.groundingKey]?.toRecord()
                ?: throw DatabaseContractViolationException(
                    "Applied knowledge research resolution is missing",
                )
        }
    }

    private suspend fun applyReviewedKnowledgePackInTransaction(
        command: ApplyReviewedKnowledgePackCommand,
    ): List<KnowledgeGroundingResolutionRecord> {
        val dependencies = readKnowledgeBaseDependencies(
            command.sources,
            command.nodes,
            command.bindings,
        )
        val resolutions = ReviewedKnowledgePackContract.validate(
            command = command,
            existingSources = dependencies.sources,
            existingParentNodes = dependencies.parentNodes,
        )
        val relationNodeIds = command.relations.flatMapTo(mutableSetOf()) {
            listOf(it.prerequisiteKnowledgeNodeId, it.dependentKnowledgeNodeId)
        }
        val relationSourceIds = command.relations.mapTo(
            mutableSetOf(),
            KnowledgeNodeRelationRecord::sourceId,
        )
        val relationSubjects = command.relations.mapTo(
            mutableSetOf(),
            KnowledgeNodeRelationRecord::subject,
        )
        KnowledgeNodeRelationContract.validate(
            incoming = command.relations,
            nodes = (command.nodes + readKnowledgeNodesByIds(
                relationNodeIds - command.nodes.mapTo(mutableSetOf(), KnowledgeNodeSeedRecord::knowledgeNodeId),
            )).distinctBy(KnowledgeNodeSeedRecord::knowledgeNodeId),
            sources = (command.sources + readKnowledgeSourcesByIds(
                relationSourceIds - command.sources.mapTo(mutableSetOf(), KnowledgeSourceSeedRecord::sourceId),
            )).distinctBy(KnowledgeSourceSeedRecord::sourceId),
            existing = relationSubjects.flatMap { subject ->
                require(database.knowledgeNodeRelationDao().countBySubject(subject) <= 16_384) {
                    "knowledge relation graph exceeds the supported validation budget"
                }
                database.knowledgeNodeRelationDao().readBySubject(subject, 16_384)
            }.map(KnowledgeNodeRelationEntity::toRecord),
        )
        return database.knowledgeGroundingDao().applyReviewedPack(
            sources = command.sources.map(KnowledgeSourceSeedRecord::toEntity),
            nodes = command.nodes.map(KnowledgeNodeSeedRecord::toEntity),
            sourceBindings = command.bindings.map(KnowledgeNodeSourceBindingSeedRecord::toEntity),
            relations = command.relations.map(KnowledgeNodeRelationRecord::toEntity),
            searchFeatures = command.nodes.flatMap(KnowledgeNodeSeedRecord::toSearchFeatures),
            resolutions = resolutions,
        ).map(KnowledgeGroundingResolutionEntity::toRecord)
    }

    private suspend fun readKnowledgeBaseDependencies(
        sources: List<KnowledgeSourceSeedRecord>,
        nodes: List<KnowledgeNodeSeedRecord>,
        bindings: List<KnowledgeNodeSourceBindingSeedRecord>,
    ): KnowledgeBaseDependencies {
        val newNodeIds = nodes.mapTo(mutableSetOf(), KnowledgeNodeSeedRecord::knowledgeNodeId)
        val newSourceIds = sources.mapTo(mutableSetOf(), KnowledgeSourceSeedRecord::sourceId)
        return KnowledgeBaseDependencies(
            parentNodes = readKnowledgeNodesByIds(
                nodes.mapNotNullTo(
                    mutableSetOf(),
                    KnowledgeNodeSeedRecord::parentKnowledgeNodeId,
                ) - newNodeIds,
            ),
            sources = readKnowledgeSourcesByIds(
                bindings.mapTo(
                    mutableSetOf(),
                    KnowledgeNodeSourceBindingSeedRecord::sourceId,
                ) - newSourceIds,
            ),
        )
    }

    override fun observePendingKnowledgeGroundingRequests(
        limit: Int,
    ): Flow<List<KnowledgeGroundingRequestRecord>> {
        require(limit in 1..512) { "Knowledge-grounding queue limit must be in 1..512" }
        return database.knowledgeGroundingDao().observePending(limit).map { requests ->
            requests.map(KnowledgeGroundingRequestEntity::toRecord)
        }
    }

    override fun observePendingKnowledgeGroundingSummaries(
        limit: Int,
    ): Flow<List<KnowledgeGroundingSummaryRecord>> {
        require(limit in 1..256) { "Knowledge-grounding summary limit must be in 1..256" }
        return database.knowledgeGroundingDao().observePendingSummaries(limit).map { summaries ->
            summaries.map(KnowledgeGroundingSummaryRow::toRecord)
        }
    }

    override fun observeReviewedKnowledgeCoverage(): Flow<List<ReviewedKnowledgeCoverageRecord>> =
        database.problemOrganizationDao().observeReviewedKnowledgeCoverage().map { rows ->
            rows.map(ReviewedKnowledgeCoverageRow::toRecord)
        }

    override suspend fun enqueueKnowledgeResearchReviewBundle(
        bundle: KnowledgeResearchReviewBundleRecord,
    ) = knowledgeResearchReviewStore.enqueue(bundle)

    override suspend fun readPendingKnowledgeResearchReviewBundles(
        limit: Int,
    ): List<KnowledgeResearchReviewBundleRecord> =
        knowledgeResearchReviewStore.readPending(limit)

    override suspend fun readKnowledgeResearchReviewBundle(
        bundleId: String,
    ): KnowledgeResearchReviewBundleRecord? =
        knowledgeResearchReviewStore.read(bundleId)

    override suspend fun decideKnowledgeResearchReviewBundle(
        command: DecideKnowledgeResearchReviewBundleCommand,
    ): KnowledgeResearchReviewBundleRecord =
        knowledgeResearchReviewStore.decide(command)

    override suspend fun recordKnowledgeGroundingRequests(
        requests: List<KnowledgeGroundingRequestRecord>,
    ) {
        KnowledgeGroundingRequestContract.validate(requests)
        database.knowledgeGroundingDao().recordAll(
            requests.map(KnowledgeGroundingRequestRecord::toEntity),
        )
    }

    override suspend fun resolveKnowledgeGrounding(
        command: ResolveKnowledgeGroundingCommand,
    ): KnowledgeGroundingResolutionRecord {
        val resolutionId = KnowledgeGroundingResolutionContract.validate(command)
        return database.knowledgeGroundingDao()
            .resolve(command, resolutionId)
            .toRecord()
    }

    override suspend fun readKnowledgeGroundingResolution(
        groundingKey: String,
    ): KnowledgeGroundingResolutionRecord? =
        database.knowledgeGroundingDao().readResolution(groundingKey)?.toRecord()

    override suspend fun findMistakeBySourceKey(sourceKey: String): MistakeRecord? {
        require(sourceKey.isNotBlank()) { "sourceKey must not be blank" }
        return database.problemDao().findMistakeBySourceKey(sourceKey)?.toRecord()
    }

    override suspend fun readMistakeDetail(errorBookEntryId: String): MistakeDetailRecord? {
        require(errorBookEntryId.isNotBlank()) { "errorBookEntryId must not be blank" }
        return database.mistakeDetailDao().read(errorBookEntryId)
    }

    override suspend fun readExactMistakeDetail(
        entryId: String,
        problemId: String,
        problemRevisionId: String,
    ): MistakeDetailRecord? {
        require(entryId.isNotBlank()) { "entryId must not be blank" }
        require(problemId.isNotBlank()) { "problemId must not be blank" }
        require(problemRevisionId.isNotBlank()) { "problemRevisionId must not be blank" }
        return database.mistakeDetailDao().readExact(entryId, problemId, problemRevisionId)
    }

    override suspend fun readCurrentMistakeDetails(
        entryIds: List<String>,
    ): List<MistakeDetailRecord> {
        require(entryIds.size <= 100) { "Mistake-detail batch is too large" }
        require(entryIds.all(String::isNotBlank)) { "entryIds must not contain blank values" }
        return database.mistakeDetailDao().readCurrentBatch(entryIds)
    }

    override suspend fun readMistakeRevisionHistory(
        errorBookEntryId: String,
    ): List<MistakeRevisionSummaryRecord> {
        require(errorBookEntryId.isNotBlank()) { "errorBookEntryId must not be blank" }
        return database.mistakeDetailDao().readRevisionHistory(errorBookEntryId)
    }

    override suspend fun createProblemDraft(
        command: CreateProblemDraftCommand,
    ): ProblemDraftWriteResult = database.problemDraftTransactionDao().create(command)

    override suspend fun appendProblemDraftSourceAsset(
        command: AppendProblemDraftSourceAssetCommand,
    ): AppendProblemDraftSourceAssetResult =
        database.problemDraftTransactionDao().appendSourceAsset(command)

    override suspend fun reviseProblemDraft(
        command: ReviseProblemDraftCommand,
    ): ProblemDraftWriteResult = database.problemDraftTransactionDao().revise(command)

    override suspend fun replaceProblemDraft(
        command: ReplaceProblemDraftCommand,
    ): ProblemDraftReplacementResult = database.withWriteTransaction {
        val draft = database.problemDraftTransactionDao().read(command.replacedDraftId)
        val workspace = draft
            ?.takeIf { it.status == StudyDbValue.ProblemDraftStatus.EDITING }
            ?.let { database.problemDraftEditWorkspaceDao().read(it.draftId) }
        val result = database.problemDraftTransactionDao().replace(command)
        workspace?.let { database.problemDraftEditWorkspaceDao().deleteExactAfterFinalization(it.toConsumeCommand()) }
        result
    }

    override suspend fun splitProblemDraft(
        command: SplitProblemDraftCommand,
    ): ProblemDraftSplitResult = database.withWriteTransaction {
        val draft = database.problemDraftTransactionDao().read(command.replacedDraftId)
        val workspace = draft
            ?.takeIf { it.status == StudyDbValue.ProblemDraftStatus.EDITING }
            ?.let { database.problemDraftEditWorkspaceDao().read(it.draftId) }
        val result = database.problemDraftTransactionDao().split(command)
        workspace?.let {
            database.problemDraftEditWorkspaceDao().deleteExactAfterFinalization(
                it.toConsumeCommand(),
            )
        }
        result
    }

    override suspend fun readProblemDraft(draftId: String): ProblemDraftRecord? {
        require(draftId.isNotBlank()) { "draftId must not be blank" }
        return database.problemDraftTransactionDao().read(draftId)
    }

    override suspend fun readCanonicalSourceAsset(
        sourceAssetId: String,
    ): CanonicalSourceAssetRecord? {
        require(sourceAssetId.isNotBlank()) { "sourceAssetId must not be blank" }
        return database.problemDraftTransactionDao().readCanonicalSourceAsset(sourceAssetId)
    }

    override suspend fun readUnreferencedCanonicalAssets(): List<CanonicalSourceAssetRecord> =
        database.pendingCaptureDao()
            .findUnreferencedCanonicalAssets()
            .map(CanonicalSourceAssetRow::toRecord)

    override suspend fun deleteUnreferencedCanonicalAssets(): Int =
        database.pendingCaptureDao().deleteUnreferencedCanonicalAssets()

    override suspend fun insertOrphanCanonicalAssetForTest(asset: CanonicalSourceAssetRecord) {
        database.useConnection(isReadOnly = false) { connection ->
            connection.usePrepared(
                "INSERT OR IGNORE INTO canonical_source_asset (" +
                    "source_asset_id, content_sha256, relative_path, mime_type, byte_size, " +
                    "width, height, source_type, created_at_epoch_millis" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ) { statement ->
                statement.bindText(1, asset.sourceAssetId)
                statement.bindText(2, asset.contentSha256)
                statement.bindText(3, asset.relativePath)
                statement.bindText(4, asset.mimeType)
                statement.bindLong(5, asset.byteSize)
                statement.bindLong(6, asset.width.toLong())
                statement.bindLong(7, asset.height.toLong())
                statement.bindText(8, asset.sourceType)
                statement.bindLong(9, asset.createdAtEpochMillis)
                statement.step()
            }
        }
    }

    override suspend fun readPendingCaptureDraft(draftId: String): PendingCaptureDraftRecord? {
        require(draftId.isNotBlank()) { "draftId must not be blank" }
        val index = database.pendingCaptureDao().readPending(draftId) ?: return null
        return loadPendingCapture(index)
    }

    override suspend fun createBatchImportJob(
        command: CreateBatchImportJobCommand,
    ): BatchImportJobRecord = batchImports.create(command)

    override suspend fun readBatchImportJob(jobId: String): BatchImportJobRecord? =
        batchImports.read(jobId)

    override suspend fun updateBatchImportJobStatus(
        jobId: String,
        expectedStatus: String,
        nextStatus: String,
        occurredAtEpochMillis: Long,
    ): Boolean = batchImports.updateStatus(
        jobId,
        expectedStatus,
        nextStatus,
        occurredAtEpochMillis,
    )

    override suspend fun requeueInterruptedBatchImportPages(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Int = batchImports.requeueInterrupted(jobId, occurredAtEpochMillis)

    override suspend fun claimNextBatchImportPage(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): BatchImportPageRecord? = batchImports.claimNext(jobId, occurredAtEpochMillis)

    override suspend fun completeBatchImportPage(
        jobId: String,
        pageIndex: Int,
        draftId: String,
        occurredAtEpochMillis: Long,
    ): Boolean = batchImports.completePage(jobId, pageIndex, draftId, occurredAtEpochMillis)

    override suspend fun claimBatchImportBoundary(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = batchImports.claimBoundary(jobId, pageIndex, occurredAtEpochMillis)

    override suspend fun requeueInterruptedBatchImportBoundaries(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Int = batchImports.requeueInterruptedBoundaries(jobId, occurredAtEpochMillis)

    override suspend fun resolveBatchImportBoundary(
        command: ResolveBatchImportBoundaryCommand,
    ): BatchImportJobRecord {
        require(command.jobId.isNotBlank())
        require(command.pageIndex >= 0)
        require(command.primaryDraftId.isNotBlank())
        require(command.followingDraftId.isNotBlank())
        require(command.occurredAtEpochMillis >= 0)
        require(
            command.resolution == StudyDbValue.BatchImportBoundaryStatus.SAME_QUESTION ||
                command.resolution == StudyDbValue.BatchImportBoundaryStatus.NEXT_QUESTION ||
                command.resolution == StudyDbValue.BatchImportBoundaryStatus.KEPT_SEPARATE,
        )
        database.withWriteTransaction {
            val batchDao = database.batchImportDao()
            val pages = batchDao.readPages(command.jobId)
            val primaryPage = pages.getOrNull(command.pageIndex)
                ?: throw ImmutablePayloadConflictException(
                    "batch_import_boundary",
                    "${command.jobId}:${command.pageIndex}",
                )
            val followingPage = pages.getOrNull(command.pageIndex + 1)
                ?: throw ImmutablePayloadConflictException(
                    "batch_import_boundary",
                    "${command.jobId}:${command.pageIndex}",
                )
            if (
                primaryPage.pageIndex != command.pageIndex ||
                followingPage.pageIndex != command.pageIndex + 1 ||
                primaryPage.status != StudyDbValue.BatchImportPageStatus.READY ||
                followingPage.status != StudyDbValue.BatchImportPageStatus.READY ||
                primaryPage.boundaryAfterStatus !=
                StudyDbValue.BatchImportBoundaryStatus.CHECKING ||
                primaryPage.resultDraftId != command.primaryDraftId ||
                followingPage.resultDraftId != command.followingDraftId
            ) {
                throw ImmutablePayloadConflictException(
                    "batch_import_boundary",
                    "${command.jobId}:${command.pageIndex}",
                )
            }

            if (
                command.resolution == StudyDbValue.BatchImportBoundaryStatus.SAME_QUESTION &&
                command.primaryDraftId != command.followingDraftId
            ) {
                val draftDao = database.problemDraftTransactionDao()
                val primary = draftDao.read(command.primaryDraftId)
                    ?: throw ImmutablePayloadConflictException(
                        "problem_draft",
                        command.primaryDraftId,
                    )
                val following = draftDao.read(command.followingDraftId)
                    ?: throw ImmutablePayloadConflictException(
                        "problem_draft",
                        command.followingDraftId,
                    )
                if (
                    database.problemDraftEditWorkspaceDao().read(command.primaryDraftId) != null ||
                    database.problemDraftEditWorkspaceDao().read(command.followingDraftId) != null
                ) {
                    throw ImmutablePayloadConflictException(
                        "problem_draft_bundle_workspace",
                        command.primaryDraftId,
                    )
                }
                draftDao.mergeSourceBundle(
                    primaryDraftId = command.primaryDraftId,
                    expectedPrimaryRevisionNumber = primary.currentRevision.revisionNumber,
                    followingDraftId = command.followingDraftId,
                    expectedFollowingRevisionNumber = following.currentRevision.revisionNumber,
                    mergedAtEpochMillis = command.occurredAtEpochMillis,
                )
                check(
                    batchDao.remapDraft(
                        jobId = command.jobId,
                        followingDraftId = command.followingDraftId,
                        primaryDraftId = command.primaryDraftId,
                        updatedAtEpochMillis = command.occurredAtEpochMillis,
                    ) > 0,
                ) { "Merged batch draft was not referenced by its batch" }
            }
            check(
                batchDao.resolveBoundary(
                    jobId = command.jobId,
                    pageIndex = command.pageIndex,
                    resolution = command.resolution,
                    updatedAtEpochMillis = command.occurredAtEpochMillis,
                ) == 1,
            ) { "Claimed batch boundary could not be resolved" }
            batchDao.touchJob(command.jobId, command.occurredAtEpochMillis)
        }
        return checkNotNull(batchImports.read(command.jobId))
    }

    override suspend fun failBatchImportBoundary(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = batchImports.failBoundary(jobId, pageIndex, occurredAtEpochMillis)

    override suspend fun failBatchImportPage(
        jobId: String,
        pageIndex: Int,
        failureCode: String,
        occurredAtEpochMillis: Long,
    ): Boolean = batchImports.failPage(jobId, pageIndex, failureCode, occurredAtEpochMillis)

    override suspend fun retryBatchImportPage(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = batchImports.retryPage(jobId, pageIndex, occurredAtEpochMillis)

    override suspend fun skipBatchImportPage(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = batchImports.skipPage(jobId, pageIndex, occurredAtEpochMillis)

    override suspend fun finishBatchImportIfSettled(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Boolean = batchImports.finishIfSettled(jobId, occurredAtEpochMillis)

    override suspend fun hasRetainedBatchImportSourceUri(sourceUri: String): Boolean {
        return batchImports.hasRetainedSourceUri(sourceUri)
    }

    override suspend fun readProblemDraftEditWorkspace(
        draftId: String,
    ): ProblemDraftEditWorkspaceRecord? =
        database.problemDraftEditWorkspaceDao().read(draftId)

    override suspend fun saveProblemDraftEditWorkspace(
        command: SaveProblemDraftEditWorkspaceCommand,
    ): ProblemDraftEditWorkspaceWriteResult =
        database.problemDraftEditWorkspaceDao().save(command)

    override suspend fun consumeProblemDraftEditWorkspace(
        command: ConsumeProblemDraftEditWorkspaceCommand,
    ): Boolean = database.problemDraftEditWorkspaceDao().consume(command)

    override suspend fun commitProblemDraft(
        command: CommitProblemDraftCommand,
    ): CommitProblemDraftResult = database.problemDraftTransactionDao().commit(command)

    override suspend fun confirmAndCommitProblemDraftFromWorkspace(
        command: ConfirmAndCommitProblemDraftFromWorkspaceCommand,
    ): CommitProblemDraftResult = database.withWriteTransaction {
        DatabaseContractValidator.validateConfirmAndCommitProblemDraftFromWorkspace(command)
        database.problemDraftTransactionDao().replayCommit(
            command = command.commit,
            allowTutorDraft = false,
        )?.let { return@withWriteTransaction it }
        val workspace = database.problemDraftEditWorkspaceDao()
            .requireExactForConfirmation(command.workspace)
        val confirmedRevision = workspace.toConfirmedRevision(command.workspace)
        database.problemDraftTransactionDao().revise(
            ReviseProblemDraftCommand(
                draftId = command.workspace.draftId,
                expectedRevisionNumber = command.workspace.basisRevisionNumber,
                revision = confirmedRevision,
            ),
        )
        val result = database.problemDraftTransactionDao().commit(command.commit)
        database.problemDraftEditWorkspaceDao().deleteExactAfterFinalization(
            command.workspace.toConsumeCommand(),
        )
        result
    }

    override suspend fun confirmTutorSession(
        command: ConfirmTutorSessionCommand,
    ): TutorSessionWriteResult = database.problemDraftTransactionDao().confirmTutorSession(command)

    override suspend fun confirmTutorSessionFromWorkspace(
        command: ConfirmTutorSessionFromWorkspaceCommand,
    ): TutorSessionWriteResult = database.withWriteTransaction {
        DatabaseContractValidator.validateConfirmTutorSessionFromWorkspace(command)
        database.problemDraftTransactionDao().readTutorSession(command.sessionId)?.let { existing ->
            if (
                existing.draftId != command.workspace.draftId ||
                existing.draftRevisionNumber != command.workspace.basisRevisionNumber + 1 ||
                existing.createdAtEpochMillis != command.workspace.finalOccurredAtEpochMillis
            ) {
                throw ImmutablePayloadConflictException("tutor_session", command.sessionId)
            }
            return@withWriteTransaction TutorSessionWriteResult(
                created = false,
                session = existing,
            )
        }
        val workspace = database.problemDraftEditWorkspaceDao()
            .requireExactForConfirmation(command.workspace)
        val result = database.problemDraftTransactionDao().confirmTutorSession(
            ConfirmTutorSessionCommand(
                sessionId = command.sessionId,
                draftId = command.workspace.draftId,
                expectedRevisionNumber = command.workspace.basisRevisionNumber,
                confirmedRevision = workspace.toConfirmedRevision(command.workspace),
                createdAtEpochMillis = command.workspace.finalOccurredAtEpochMillis,
            ),
        )
        database.problemDraftEditWorkspaceDao().deleteExactAfterFinalization(
            command.workspace.toConsumeCommand(),
        )
        result
    }

    override suspend fun readTutorSession(sessionId: String): TutorSessionRecord? {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        return database.problemDraftTransactionDao().readTutorSession(sessionId)
    }

    override suspend fun createTutorConversation(
        command: CreateTutorConversationDatabaseCommand,
    ): TutorConversationRecord = database.tutorConversationDao().createConversation(command)

    override suspend fun appendTutorStudentMessage(
        command: AppendTutorStudentMessageDatabaseCommand,
    ): TutorMessageRecord = database.tutorConversationDao().appendStudentMessage(command)

    override suspend fun appendTutorAssistantMessage(
        command: AppendTutorAssistantMessageDatabaseCommand,
    ): TutorMessageRecord = database.tutorConversationDao().appendAssistantMessage(command)

    override suspend fun updateTutorMessageStatus(
        command: UpdateTutorMessageStatusDatabaseCommand,
    ): TutorMessageRecord = database.tutorConversationDao().updateMessageStatus(command)

    override suspend fun pauseTutorConversation(
        conversationId: String,
        updatedAtEpochMillis: Long,
    ): TutorConversationRecord = database.tutorConversationDao().pauseConversation(
        conversationId = conversationId,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    override suspend fun archiveTutorConversation(
        conversationId: String,
        updatedAtEpochMillis: Long,
    ): TutorConversationRecord = database.tutorConversationDao().archiveConversation(
        conversationId = conversationId,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    override suspend fun deleteTutorConversation(conversationId: String) {
        database.tutorConversationDao().deleteConversation(conversationId)
    }

    override suspend fun saveTutorConversationDraft(
        conversationId: String,
        draft: String,
        updatedAtEpochMillis: Long,
    ) {
        database.tutorConversationDao().saveStudentDraft(
            SaveTutorConversationDraftDatabaseCommand(
                conversationId = conversationId,
                draft = draft,
                updatedAtEpochMillis = updatedAtEpochMillis,
            ),
        )
    }

    override suspend fun clearTutorConversationDraft(
        conversationId: String,
        updatedAtEpochMillis: Long,
    ) {
        database.tutorConversationDao().clearStudentDraft(
            ClearTutorConversationDraftDatabaseCommand(
                conversationId = conversationId,
                updatedAtEpochMillis = updatedAtEpochMillis,
            ),
        )
    }

    override suspend fun commitTutorSession(
        command: CommitTutorSessionCommand,
    ): CommitProblemDraftResult = database.withWriteTransaction {
        val result = database.problemDraftTransactionDao().commitTutorSession(command)
        database.tutorExposureDao().bindAnchor(
            PersistTutorSessionAnchorCommand(
                sessionId = command.sessionId,
                problemRevisionId = result.receipt.problemRevisionId,
                practiceUnitId = result.receipt.practiceUnitId,
                source = "DRAFT_COMMIT",
                anchoredAtEpochMillis = result.receipt.committedAtEpochMillis,
            ),
        )
        result
    }

    override suspend fun endTutorSession(
        command: EndTutorSessionCommand,
    ): EndTutorSessionResult = database.withWriteTransaction {
        val session = database.problemDraftTransactionDao().readTutorSession(command.sessionId)
        val workspace = session?.let { database.problemDraftEditWorkspaceDao().read(it.draftId) }
        val result = database.problemDraftTransactionDao().endTutorSession(command)
        workspace?.let { database.problemDraftEditWorkspaceDao().deleteExactAfterFinalization(it.toConsumeCommand()) }
        result
    }

    override suspend fun recordTutorChoice(
        command: PersistTutorChoiceCommand,
    ): TutorTurnResponseRecord = database.tutorInteractionDao().recordChoice(command)

    override suspend fun recordTutorMove(
        command: PersistTutorMoveCommand,
    ): TutorTurnResponseRecord = database.tutorInteractionDao().recordMove(command)

    override suspend fun revealTutorSolution(
        command: PersistTutorRevealCommand,
    ): TutorTurnResponseRecord = database.tutorInteractionDao().revealSolution(command)

    override suspend fun recordTutorSolutionExposure(
        command: PersistTutorAnswerExposureCommand,
    ): TutorAnswerExposureRecord = database.tutorExposureDao().recordVisibleExposure(command)

    override suspend fun bindTutorSessionProblemAnchor(
        command: PersistTutorSessionAnchorCommand,
    ): TutorSessionProblemAnchorRecord = database.tutorExposureDao().bindAnchor(command)

    override suspend fun reconcileTutorAnswerExposures(learnerId: String, limit: Int): Int =
        database.tutorExposureDao().reconcilePending(learnerId, limit)

    override suspend fun readTutorAnswerExposure(
        modelTaskRequestId: String,
    ): TutorAnswerExposureRecord? =
        database.tutorExposureDao().readExposure(modelTaskRequestId)

    override suspend fun readTutorAnswerExposures(
        modelTaskRequestIds: Set<String>,
    ): List<TutorAnswerExposureRecord> =
        database.tutorExposureDao().readExposures(modelTaskRequestIds)

    private suspend fun loadPendingCaptureBatch(): List<PendingCaptureDraftRecord> =
        database.withReadTransaction {
            val pending = database.pendingCaptureDao()
            val heads = pending.readPendingHeads()
            if (heads.isEmpty()) return@withReadTransaction emptyList()

            val sourceAssetsByDraft = pending.readPendingSourceAssets().groupBy { it.draftId }
            val assessmentTasksByDraft = pending.readRecentPendingAssessmentTasks()
                .groupBy { it.subjectId }
                .mapValues { (_, tasks) -> tasks.map { it.toSnapshot() } }
            val parseTasksByDraft = pending.readLatestPendingParseTasks()
                .groupBy { it.subjectId }
                .mapValues { (draftId, tasks) ->
                    if (tasks.size != 1) {
                        throw LearningLedgerIntegrityException(
                            "Pending draft $draftId has multiple latest parse tasks",
                        )
                    }
                    tasks.single().toSnapshot()
                }

            heads.map { head ->
                val draft = head.toProblemDraftRecord(
                    sourceAssetsByDraft[head.draftId].orEmpty(),
                )
                validatePendingTutorSession(
                    draft = draft,
                    sessionId = head.tutorSessionId,
                    sessionRevision = head.tutorSessionDraftRevisionNumber,
                )
                val assessments = assessmentTasksByDraft[head.draftId].orEmpty()
                PendingCaptureDraftRecord(
                    draft = draft,
                    editWorkspace = head.toValidatedWorkspaceRecord(draft),
                    latestAssessmentTask = assessments.firstOrNull(),
                    assessmentTasks = assessments,
                    latestParseTask = parseTasksByDraft[head.draftId],
                    tutorSessionId = head.tutorSessionId,
                    tutorSessionDraftRevisionNumber = head.tutorSessionDraftRevisionNumber,
                )
            }
        }

    private suspend fun loadPendingCapture(
        index: PendingCaptureIndexRow,
    ): PendingCaptureDraftRecord? {
        val draft = database.problemDraftTransactionDao().read(index.draftId) ?: return null
        if (
            draft.status != StudyDbValue.ProblemDraftStatus.EDITING ||
            draft.updatedAtEpochMillis != index.draftUpdatedAtEpochMillis
        ) {
            return null
        }
        val sessionId = index.tutorSessionId
        val sessionRevision = index.tutorSessionDraftRevisionNumber
        validatePendingTutorSession(draft, sessionId, sessionRevision)
        return PendingCaptureDraftRecord(
            draft = draft,
            editWorkspace = index.toValidatedWorkspaceRecord(draft),
            latestAssessmentTask = index.latestAssessmentRequestId?.let {
                database.modelTaskTransactionDao().read(it)
            },
            assessmentTasks = database.pendingCaptureDao()
                .readAssessmentRequestIds(index.draftId)
                .mapNotNull { database.modelTaskTransactionDao().read(it) },
            latestParseTask = index.latestParseRequestId?.let {
                database.modelTaskTransactionDao().read(it)
            },
            tutorSessionId = sessionId,
            tutorSessionDraftRevisionNumber = sessionRevision,
        )
    }

    private fun validatePendingTutorSession(
        draft: ProblemDraftRecord,
        sessionId: String?,
        sessionRevision: Int?,
    ) {
        if ((sessionId == null) != (sessionRevision == null)) {
            throw LearningLedgerIntegrityException("Pending tutor-session columns are incomplete")
        }
        if (
            sessionId != null &&
            (draft.origin != StudyDbValue.CaptureOrigin.TUTOR ||
                sessionRevision != draft.currentRevision.revisionNumber)
        ) {
            throw LearningLedgerIntegrityException("Pending tutor session disagrees with its draft")
        }
    }

    private fun PendingCaptureWorkspaceColumns.toValidatedWorkspaceRecord(
        draft: ProblemDraftRecord,
    ): ProblemDraftEditWorkspaceRecord? {
        val columns = listOf(
            workspaceBasisRevisionNumber,
            workspaceVersion,
            workspaceSnapshotSchemaVersion,
            workspaceSnapshot,
            workspaceFingerprint,
            workspaceCreatedAtEpochMillis,
            workspaceUpdatedAtEpochMillis,
        )
        if (columns.all { it == null }) return null
        if (columns.any { it == null }) {
            throw ProblemDraftEditWorkspaceIntegrityException(
                "Pending workspace ${draft.draftId} has incomplete columns",
            )
        }
        val record = ProblemDraftEditWorkspaceRecord(
            draftId = draft.draftId,
            basisRevisionNumber = checkNotNull(workspaceBasisRevisionNumber),
            workspaceVersion = checkNotNull(workspaceVersion),
            snapshotSchemaVersion = checkNotNull(workspaceSnapshotSchemaVersion),
            workspaceSnapshot = checkNotNull(workspaceSnapshot),
            workspaceFingerprint = checkNotNull(workspaceFingerprint),
            createdAtEpochMillis = checkNotNull(workspaceCreatedAtEpochMillis),
            updatedAtEpochMillis = checkNotNull(workspaceUpdatedAtEpochMillis),
        )
        val workspace = try {
            DatabaseContractValidator.decodeProblemDraftEditWorkspace(
                snapshotSchemaVersion = record.snapshotSchemaVersion,
                workspaceSnapshot = record.workspaceSnapshot,
                workspaceFingerprint = record.workspaceFingerprint,
            )
        } catch (failure: Exception) {
            throw ProblemDraftEditWorkspaceIntegrityException(
                "Pending workspace ${draft.draftId} is corrupted",
                failure,
            )
        }
        if (
            draft.status != StudyDbValue.ProblemDraftStatus.EDITING ||
            record.basisRevisionNumber != draft.currentRevision.revisionNumber ||
            record.workspaceVersion <= 0 ||
            record.createdAtEpochMillis < draft.currentRevision.createdAtEpochMillis ||
            record.updatedAtEpochMillis < record.createdAtEpochMillis ||
            workspace.baseCandidateFingerprint != draft.currentRevision.documentFingerprint ||
            workspace.workingDocument.blockEvidence.any {
                it.sourceAssetId != draft.sourceAsset.sourceAssetId
            }
        ) {
            throw ProblemDraftEditWorkspaceIntegrityException(
                "Pending workspace ${draft.draftId} has a stale or invalid binding",
            )
        }
        return record
    }

    override fun observeModelTask(
        requestId: String,
    ) = database.modelTaskTransactionDao().observe(requestId)

    override fun observeModelTasks(
        subjectId: String,
        kind: com.tingyun.smartmistakebook.core.model.ModelTaskKind,
    ) = database.modelTaskTransactionDao().observeBySubject(subjectId, kind)

    override fun observeRecentModelTasks(
        subjectId: String,
        kind: com.tingyun.smartmistakebook.core.model.ModelTaskKind,
        limit: Int,
    ) = database.modelTaskTransactionDao().observeRecentBySubject(subjectId, kind, limit)

    override suspend fun readModelTask(requestId: String) =
        database.modelTaskTransactionDao().read(requestId)

    override suspend fun createModelTask(command: CreateModelTaskCommand) =
        database.modelTaskTransactionDao().create(command)

    override suspend fun reserveModelTaskRemoteDispatch(
        command: ReserveModelTaskRemoteDispatchCommand,
    ) = database.modelTaskTransactionDao().reserveRemoteDispatch(command)

    override suspend fun transitionModelTask(command: TransitionModelTaskCommand) =
        database.modelTaskTransactionDao().transition(command)

    override suspend fun seedFixture(bundle: StudySeedBundle): SeedResult {
        DatabaseContractValidator.validateSeedBundle(bundle)
        val result = database.fixtureSeedDao().seed(
            problems = bundle.problems.map(ProblemSeedRecord::toEntity),
            revisions = bundle.revisions.map(ProblemRevisionSeedRecord::toEntity),
            practiceUnits = bundle.practiceUnits.map(PracticeUnitSeedRecord::toEntity),
            errorBookEntries = bundle.errorBookEntries.map(ErrorBookEntrySeedRecord::toEntity),
            knowledgeNodes = bundle.knowledgeNodes.map(KnowledgeNodeSeedRecord::toEntity),
            knowledgeBindings = bundle.knowledgeBindings.map(KnowledgeBindingSeedRecord::toEntity),
            relations = bundle.relations.map(ProblemRelationSeedRecord::toEntity),
            assessmentItems = bundle.assessmentItems.map(AssessmentItemSnapshotSeedRecord::toEntity),
            assessmentEvents = bundle.assessmentEvents.map(AssessmentEventSeedRecord::toEntity),
            memoryStates = bundle.problemMemoryStates.map(ProblemMemoryStateRecord::toEntity),
            masteryStates = bundle.knowledgeMasteryStates.map(KnowledgeMasteryStateRecord::toEntity),
            reviewPlans = bundle.reviewPlans.map(ReviewPlanRecord::toEntity),
            reviewQueueItems = bundle.reviewQueueItems.map(ReviewQueueItemRecord::toEntity),
            reviewQueueKnowledgeNodes = bundle.reviewQueueItems.flatMap(
                ReviewQueueItemRecord::toKnowledgeNodeEntities,
            ),
            reviewQueueReasons = bundle.reviewQueueItems.flatMap(
                ReviewQueueItemRecord::toReasonEntities,
            ),
            reviewSessions = bundle.reviewSessions.map(ReviewSessionRecord::toEntity),
            reviewSessionRevisions = bundle.reviewSessions.map(ReviewSessionRecord::toRevisionEntity),
        )
        return SeedResult(
            insertedProblemCount = result.insertedProblemCount,
            insertedErrorBookEntryCount = result.insertedErrorBookEntryCount,
        )
    }

    override suspend fun saveAssessmentItemSnapshot(item: AssessmentItemSnapshotSeedRecord) {
        DatabaseContractValidator.validateAssessmentItem(item)
        database.immutableLearningFactDao().saveAssessmentItem(item.toEntity())
    }

    override suspend fun saveAssessmentEvidenceSnapshot(
        snapshot: com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot,
    ) {
        database.attemptTransactionDao().saveAssessmentEvidenceSnapshot(snapshot)
    }

    override suspend fun appendAssessmentEvent(event: AssessmentEventSeedRecord) {
        DatabaseContractValidator.validateAssessmentEvent(event)
        database.immutableLearningFactDao().saveAssessmentEvent(event.toEntity())
    }

    override suspend fun recordAttempt(command: AttemptWriteCommand): AttemptWriteResult {
        val result = database.attemptTransactionDao().recordAttempt(command)
        return AttemptWriteResult(
            submissionId = result.submissionId,
            created = result.created,
            attempt = result.attempt,
            canonicalFingerprint = result.canonicalFingerprint,
            outboxId = result.outbox.outboxId,
        )
    }

    override suspend fun recordReviewAttempt(
        command: ReviewAttemptWriteCommand,
    ): ReviewAttemptWriteResult = database.withWriteTransaction {
        val attempt = this@RoomStudyDatabase.recordAttempt(command.attempt)
        val persistedPracticeUnitId = attempt.attempt.assessmentSnapshot.practiceUnitId
        if (persistedPracticeUnitId != command.practiceUnitId) {
            throw ImmutablePayloadConflictException("review_attempt", attempt.attempt.attemptId)
        }
        val advanceCommand = ReviewSessionAdvanceCommand(
            sessionId = command.sessionId,
            expectedStateVersion = command.expectedStateVersion,
            reviewQueueItemId = command.reviewQueueItemId,
            practiceUnitId = persistedPracticeUnitId,
            attemptId = attempt.attempt.attemptId,
            submissionId = attempt.submissionId,
            presentationId = attempt.attempt.presentationId,
            occurredAtEpochMillis = attempt.attempt.occurredAtEpochMillis,
        )
        DatabaseContractValidator.validateReviewSessionAdvance(advanceCommand)
        val transition = database.reviewPlanTransactionDao().advanceSession(
            command = advanceCommand,
            attemptCreatedInCurrentTransaction = attempt.created,
        )
        val advance = ReviewSessionAdvanceResult(
            created = transition.created,
            session = transition.session.toRecord(),
            receipt = transition.receipt.toRecord(),
        )
        ReviewAttemptWriteResult(attempt = attempt, advance = advance)
    }

    override suspend fun recordAnswerReveal(
        command: AnswerRevealWriteCommand,
    ): AnswerRevealWriteResult {
        val result = database.attemptTransactionDao().recordAnswerReveal(command)
        return AnswerRevealWriteResult(
            created = result.created,
            outcome = result.outcome,
            canonicalFingerprint = result.canonicalFingerprint,
            outboxId = result.outbox.outboxId,
        )
    }

    override suspend fun reconcileAnswerRevealOutcomes(
        learnerId: String,
        limit: Int,
    ): List<AnswerRevealWriteResult> = database.attemptTransactionDao()
        .reconcileAnswerRevealOutcomes(learnerId, limit)
        .map { result ->
            AnswerRevealWriteResult(
                created = result.created,
                outcome = result.outcome,
                canonicalFingerprint = result.canonicalFingerprint,
                outboxId = result.outbox.outboxId,
            )
        }

    override suspend fun appendAttemptCorrection(
        correction: AttemptCorrectionRecord,
    ): AttemptCorrectionResult {
        val result = database.attemptTransactionDao().appendCorrection(correction)
        return AttemptCorrectionResult(
            created = result.created,
            correction = result.correction,
            canonicalFingerprint = result.canonicalFingerprint,
            outboxId = result.outbox.outboxId,
        )
    }

    override suspend fun findAttemptPersistence(
        submissionId: String,
    ): AttemptPersistenceRecord? = database.learningDao().findAttemptPersistence(submissionId)

    override suspend fun findAttemptAdvanceProof(
        attemptId: String,
    ): AttemptAdvanceProofRecord? = database.learningDao().findAttemptAdvanceProof(attemptId)

    override suspend fun markRelationsStaleForRevision(
        problemRevisionId: String,
        updatedAtEpochMillis: Long,
    ): Int {
        require(problemRevisionId.isNotBlank()) { "problemRevisionId must not be blank" }
        require(updatedAtEpochMillis >= 0) { "updatedAtEpochMillis cannot be negative" }
        return database.problemDao().markRelationsStaleForRevision(
            problemRevisionId = problemRevisionId,
            staleStatus = StudyDbValue.RelationStatus.STALE,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }

    override suspend fun loadProjectionBatch(
        projectionName: String,
        learnerId: String,
        limit: Int,
    ): ProjectionBatch = database.projectionTransactionDao()
        .loadProjectionBatch(projectionName, learnerId, limit)

    override suspend fun loadLearningLedger(learnerId: String): LearningLedgerRead =
        database.projectionTransactionDao().loadLearningLedger(learnerId)

    override suspend fun readCurrentLearnerSnapshot(
        projectionName: String,
        learnerId: String,
    ): PersistedLearnerSnapshot? = database.projectionTransactionDao()
        .readCurrentSnapshot(projectionName, learnerId)

    override suspend fun commitProjection(
        commit: ProjectionCommit,
    ): PersistedLearnerSnapshot = database.projectionTransactionDao().commitProjection(commit)

    override suspend fun saveReviewPlan(bundle: ReviewPlanBundle) {
        DatabaseContractValidator.validateReviewBundle(bundle)
        database.reviewPlanTransactionDao().savePlan(
            plan = bundle.plan.toEntity(),
            queue = bundle.queue.map(ReviewQueueItemRecord::toEntity),
            knowledgeNodes = bundle.queue.flatMap(ReviewQueueItemRecord::toKnowledgeNodeEntities),
            reasons = bundle.queue.flatMap(ReviewQueueItemRecord::toReasonEntities),
            activeSession = bundle.activeSession?.toEntity(),
            isCurrent = bundle.isCurrent,
        )
    }

    override suspend fun saveReviewSession(session: ReviewSessionRecord) {
        DatabaseContractValidator.validateReviewSessionCreation(session)
        database.reviewPlanTransactionDao().saveSession(session.toEntity())
    }

    @Deprecated("New review transitions must use recordReviewAttempt")
    override suspend fun advanceReviewSession(
        command: ReviewSessionAdvanceCommand,
    ): ReviewSessionAdvanceResult {
        DatabaseContractValidator.validateReviewSessionAdvance(command)
        val result = database.reviewPlanTransactionDao().advanceSession(
            command = command,
            attemptCreatedInCurrentTransaction = false,
        )
        return ReviewSessionAdvanceResult(
            created = result.created,
            session = result.session.toRecord(),
            receipt = result.receipt.toRecord(),
        )
    }

    override suspend fun readAssessmentSnapshotP0(
        assessmentItemSnapshotId: String,
    ): AssessmentItemSnapshotSeedRecord? = database.immutableLearningFactDao()
        .findAssessmentItem(assessmentItemSnapshotId)
        ?.toRecord()

    override suspend fun readAttemptP0(attemptId: String): PersistedAttemptP0? =
        database.learningDao().readAttempt(attemptId)

    override suspend fun readCorrectionP0(correctionId: String): PersistedCorrectionP0? =
        database.learningDao().readCorrection(correctionId)

    override suspend fun readAnswerRevealP0(outcomeId: String): PersistedAnswerRevealP0? =
        database.learningDao().readAnswerReveal(outcomeId)

    override fun observeConfirmedProblemOrganization(
        problemId: String,
        problemRevisionId: String,
    ): Flow<ConfirmedProblemOrganizationRecord> =
        problemOrganization.observe(problemId, problemRevisionId)

    override suspend fun confirmProblemOrganization(
        command: ConfirmProblemOrganizationCommand,
    ): ConfirmProblemOrganizationResult = problemOrganization.confirm(command)

    override fun close() = database.close()
}

private const val KNOWLEDGE_NODE_QUERY_CHUNK_SIZE = 400

private fun PendingCaptureHeadRow.toProblemDraftRecord(
    sourceRows: List<PendingCaptureSourceAssetRow>,
): ProblemDraftRecord {
    val persistedRevisionNumber = revisionNumber
        ?: throw LearningLedgerIntegrityException("Pending draft $draftId has no current revision")
    if (persistedRevisionNumber != draftCurrentRevisionNumber) {
        throw LearningLedgerIntegrityException("Pending draft $draftId has a mismatched current revision")
    }
    val sourceAssets = sourceRows.map { row ->
        ProblemDraftSourceAssetRecord(
            pageIndex = row.pageIndex,
            sourceAsset = CanonicalSourceAssetRecord(
                sourceAssetId = row.sourceAssetId,
                contentSha256 = row.contentSha256,
                relativePath = row.relativePath,
                mimeType = row.mimeType,
                byteSize = row.byteSize,
                width = row.width,
                height = row.height,
                sourceType = row.sourceType,
                createdAtEpochMillis = row.createdAtEpochMillis,
            ),
        )
    }
    val primarySource = sourceAssets.firstOrNull()?.sourceAsset
        ?: throw LearningLedgerIntegrityException("Pending draft $draftId has no source asset")
    if (primarySource.sourceAssetId != primarySourceAssetId) {
        throw LearningLedgerIntegrityException("Pending draft $draftId has a mismatched primary source")
    }
    return ProblemDraftRecord(
        draftId = draftId,
        sourceAsset = primarySource,
        sourceAssets = sourceAssets,
        origin = draftOrigin,
        status = draftStatus,
        currentRevision = ProblemDraftRevisionRecord(
            draftId = draftId,
            revisionNumber = persistedRevisionNumber,
            basisRevisionNumber = revisionBasisRevisionNumber,
            subject = revisionSubject,
            title = revisionTitle
                ?: throw LearningLedgerIntegrityException("Pending draft $draftId has no revision title"),
            questionDocument = CapturedQuestionDocumentCodec.decode(
                revisionQuestionDocumentSnapshot ?: throw LearningLedgerIntegrityException(
                    "Pending draft $draftId has no question document",
                ),
            ),
            documentFingerprint = revisionDocumentFingerprint
                ?: throw LearningLedgerIntegrityException(
                    "Pending draft $draftId has no document fingerprint",
                ),
            author = revisionAuthor
                ?: throw LearningLedgerIntegrityException("Pending draft $draftId has no revision author"),
            createdAtEpochMillis = revisionCreatedAtEpochMillis
                ?: throw LearningLedgerIntegrityException(
                    "Pending draft $draftId has no revision creation time",
                ),
        ),
        createdAtEpochMillis = draftCreatedAtEpochMillis,
        updatedAtEpochMillis = draftUpdatedAtEpochMillis,
        requestFingerprint = draftRequestFingerprint,
    )
}

private val PENDING_CAPTURE_TABLES = arrayOf(
    "problem_draft",
    "problem_draft_revision",
    "problem_draft_source_asset",
    "canonical_source_asset",
    "problem_draft_edit_snapshot",
    "tutor_session",
    "model_task",
)

private fun ProblemSeedRecord.toEntity() = ProblemEntity(
    problemId = problemId,
    canonicalFingerprint = canonicalFingerprint,
    subject = subject,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun ProblemRevisionSeedRecord.toEntity() = ProblemRevisionEntity(
    revisionId = revisionId,
    problemId = problemId,
    revisionNumber = revisionNumber,
    title = title,
    problemMarkdown = problemMarkdown,
    questionDocumentSnapshot = questionDocumentSnapshot,
    answerSpecId = answerSpecId,
    answerSpecSnapshot = answerSpecSnapshot,
    answerVerificationStatus = answerVerificationStatus,
    sourceType = sourceType,
    sourceReference = sourceReference,
    contentFingerprint = contentFingerprint,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun PracticeUnitSeedRecord.toEntity() = PracticeUnitEntity(
    practiceUnitId = practiceUnitId,
    problemId = problemId,
    problemRevisionId = problemRevisionId,
    unitKey = unitKey,
    unitKind = unitKind,
    title = title,
    promptMarkdown = promptMarkdown,
    estimatedSeconds = estimatedSeconds,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun ErrorBookEntrySeedRecord.toEntity() = ErrorBookEntryEntity(
    entryId = entryId,
    practiceUnitId = practiceUnitId,
    problemId = problemId,
    currentRevisionId = currentRevisionId,
    sourceKey = sourceKey,
    status = status,
    acceptedAtEpochMillis = acceptedAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun KnowledgeNodeSeedRecord.toEntity() = KnowledgeNodeEntity(
    knowledgeNodeId = knowledgeNodeId,
    stableCode = stableCode,
    subject = subject,
    displayName = displayName,
    canonicalName = canonicalName,
    nodeKind = nodeKind,
    granularity = granularity,
    aliasesText = aliases.sorted().joinToString("\u001F"),
    boundaryMarkdown = boundaryMarkdown,
    verificationStatus = verificationStatus,
    parentKnowledgeNodeId = parentKnowledgeNodeId,
    taxonomyVersion = taxonomyVersion,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun KnowledgeNodeSeedRecord.toSearchFeatures(): List<KnowledgeSearchFeatureEntity> =
    KnowledgeSearchFeatureExtractor.fromNode(this).map { feature ->
        KnowledgeSearchFeatureEntity(
            subject = subject,
            searchFeature = feature,
            knowledgeNodeId = knowledgeNodeId,
        )
    }

private fun KnowledgeNodeEntity.toSearchFeatures(): List<KnowledgeSearchFeatureEntity> =
    toSeedRecord().toSearchFeatures()

private fun KnowledgeNodeEntity.toSeedRecord() = KnowledgeNodeSeedRecord(
    knowledgeNodeId = knowledgeNodeId,
    stableCode = stableCode,
    subject = subject,
    displayName = displayName,
    parentKnowledgeNodeId = parentKnowledgeNodeId,
    taxonomyVersion = taxonomyVersion,
    createdAtEpochMillis = createdAtEpochMillis,
    canonicalName = canonicalName,
    nodeKind = nodeKind,
    granularity = granularity,
    aliases = aliasesText.split("\u001F").filter(String::isNotBlank).toSet(),
    boundaryMarkdown = boundaryMarkdown,
    verificationStatus = verificationStatus,
)

private fun KnowledgeSourceSeedRecord.toEntity() = KnowledgeSourceEntity(
    sourceId = sourceId,
    subject = subject,
    sourceType = sourceType,
    title = title,
    publisher = publisher,
    edition = edition,
    sourceUri = sourceUri,
    licenseStatus = licenseStatus,
    contentFingerprint = contentFingerprint,
    importedAtEpochMillis = importedAtEpochMillis,
    contentUsePolicy = contentUsePolicy,
    licenseExpression = licenseExpression,
    licenseUri = licenseUri,
    attributionText = attributionText,
)

private fun KnowledgeSourceEntity.toSeedRecord() = KnowledgeSourceSeedRecord(
    sourceId = sourceId,
    subject = subject,
    sourceType = sourceType,
    title = title,
    publisher = publisher,
    edition = edition,
    sourceUri = sourceUri,
    licenseStatus = licenseStatus,
    contentFingerprint = contentFingerprint,
    importedAtEpochMillis = importedAtEpochMillis,
    contentUsePolicy = contentUsePolicy,
    licenseExpression = licenseExpression,
    licenseUri = licenseUri,
    attributionText = attributionText,
)

private fun KnowledgeNodeRelationRecord.toEntity() = KnowledgeNodeRelationEntity(
    relationId = relationId,
    subject = subject,
    prerequisiteKnowledgeNodeId = prerequisiteKnowledgeNodeId,
    dependentKnowledgeNodeId = dependentKnowledgeNodeId,
    relationType = relationType,
    sourceId = sourceId,
    sourceLocator = sourceLocator,
    reviewedAtEpochMillis = reviewedAtEpochMillis,
)

private fun KnowledgeNodeRelationEntity.toRecord() = KnowledgeNodeRelationRecord(
    relationId = relationId,
    subject = subject,
    prerequisiteKnowledgeNodeId = prerequisiteKnowledgeNodeId,
    dependentKnowledgeNodeId = dependentKnowledgeNodeId,
    relationType = relationType,
    sourceId = sourceId,
    sourceLocator = sourceLocator,
    reviewedAtEpochMillis = reviewedAtEpochMillis,
)

private fun KnowledgeTeachingMaterialRecord.toEntity() = KnowledgeTeachingMaterialEntity(
    materialId = materialId,
    stableCode = stableCode,
    subject = subject,
    materialType = materialType,
    title = title,
    summaryMarkdown = summaryMarkdown,
    applicabilityMarkdown = applicabilityMarkdown,
    contentMarkdown = contentMarkdown,
    boundaryMarkdown = boundaryMarkdown,
    derivationKind = derivationKind,
    sourceId = sourceId,
    sourceLocator = sourceLocator,
    contentFingerprint = contentFingerprint,
    reviewedAtEpochMillis = reviewedAtEpochMillis,
)

private fun KnowledgeTeachingMaterialEntity.toRecord() = KnowledgeTeachingMaterialRecord(
    materialId = materialId,
    stableCode = stableCode,
    subject = subject,
    materialType = materialType,
    title = title,
    summaryMarkdown = summaryMarkdown,
    applicabilityMarkdown = applicabilityMarkdown,
    contentMarkdown = contentMarkdown,
    boundaryMarkdown = boundaryMarkdown,
    derivationKind = derivationKind,
    sourceId = sourceId,
    sourceLocator = sourceLocator,
    contentFingerprint = contentFingerprint,
    reviewedAtEpochMillis = reviewedAtEpochMillis,
)

private fun KnowledgeTeachingMaterialNodeBindingRecord.toEntity() =
    KnowledgeTeachingMaterialNodeBindingEntity(
        materialId = materialId,
        knowledgeNodeId = knowledgeNodeId,
        role = role,
    )

private fun KnowledgeTeachingMaterialNodeBindingEntity.toRecord() =
    KnowledgeTeachingMaterialNodeBindingRecord(
        materialId = materialId,
        knowledgeNodeId = knowledgeNodeId,
        role = role,
    )

private fun KnowledgeGroundingRequestRecord.toEntity() = KnowledgeGroundingRequestEntity(
    groundingRequestId = groundingRequestId,
    groundingKey = groundingKey,
    organizationRequestId = organizationRequestId,
    organizationRequestFingerprint = organizationRequestFingerprint,
    requestOrdinal = requestOrdinal,
    problemId = problemId,
    problemRevisionId = problemRevisionId,
    practiceUnitId = practiceUnitId,
    subject = subject,
    query = query,
    expectedParentKnowledgeDisplayName = expectedParentKnowledgeDisplayName,
    reasonMarkdown = reasonMarkdown,
    status = status,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun KnowledgeGroundingRequestEntity.toRecord() = KnowledgeGroundingRequestRecord(
    groundingRequestId = groundingRequestId,
    groundingKey = groundingKey,
    organizationRequestId = organizationRequestId,
    organizationRequestFingerprint = organizationRequestFingerprint,
    requestOrdinal = requestOrdinal,
    problemId = problemId,
    problemRevisionId = problemRevisionId,
    practiceUnitId = practiceUnitId,
    subject = subject,
    query = query,
    expectedParentKnowledgeDisplayName = expectedParentKnowledgeDisplayName,
    reasonMarkdown = reasonMarkdown,
    status = status,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun KnowledgeGroundingSummaryRow.toRecord() = KnowledgeGroundingSummaryRecord(
    groundingKey = groundingKey,
    subject = subject,
    expectedParentKnowledgeDisplayName = expectedParentKnowledgeDisplayName,
    query = query,
    relatedQuestionCount = relatedQuestionCount,
    firstObservedAtEpochMillis = firstObservedAtEpochMillis,
    lastObservedAtEpochMillis = lastObservedAtEpochMillis,
)

private data class KnowledgeBaseDependencies(
    val sources: List<KnowledgeSourceSeedRecord>,
    val parentNodes: List<KnowledgeNodeSeedRecord>,
)

private fun KnowledgeGroundingResolutionEntity.toRecord() = KnowledgeGroundingResolutionRecord(
    resolutionId = resolutionId,
    groundingKey = groundingKey,
    subject = subject,
    knowledgeNodeId = knowledgeNodeId,
    taxonomyVersion = taxonomyVersion,
    resolvedOccurrenceCount = resolvedOccurrenceCount,
    linkedPracticeUnitCount = linkedPracticeUnitCount,
    resolvedAtEpochMillis = resolvedAtEpochMillis,
)

private fun ReviewedKnowledgeCoverageRow.toRecord() = ReviewedKnowledgeCoverageRecord(
    subject = subject,
    topicCount = topicCount,
    atomicKnowledgeCount = atomicKnowledgeCount,
    reviewedSourceCount = reviewedSourceCount,
    latestReviewedAtEpochMillis = latestReviewedAtEpochMillis,
)

private fun KnowledgeNodeSourceBindingSeedRecord.toEntity() = KnowledgeNodeSourceBindingEntity(
    knowledgeNodeId = knowledgeNodeId,
    sourceId = sourceId,
    sourceLocator = sourceLocator,
    derivationNote = derivationNote,
    reviewedAtEpochMillis = reviewedAtEpochMillis,
)

private fun KnowledgeNodeSourceBindingEntity.toSeedRecord() =
    KnowledgeNodeSourceBindingSeedRecord(
        knowledgeNodeId = knowledgeNodeId,
        sourceId = sourceId,
        sourceLocator = sourceLocator,
        derivationNote = derivationNote,
        reviewedAtEpochMillis = reviewedAtEpochMillis,
    )

private fun KnowledgeBindingSeedRecord.toEntity() = PracticeUnitKnowledgeBindingEntity(
    bindingId = bindingId,
    practiceUnitId = practiceUnitId,
    knowledgeNodeId = knowledgeNodeId,
    basisRevisionId = basisRevisionId,
    strength = strength,
    sourceType = sourceType,
    taxonomyVersion = taxonomyVersion,
    acceptedAtEpochMillis = acceptedAtEpochMillis,
)

private fun ProblemRelationSeedRecord.toEntity() = ProblemRelationEntity(
    relationId = relationId,
    sourceProblemId = sourceProblemId,
    targetProblemId = targetProblemId,
    relationType = relationType,
    status = status,
    sourceBasisRevisionId = sourceBasisRevisionId,
    targetBasisRevisionId = targetBasisRevisionId,
    confidence = confidence,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun AssessmentItemSnapshotSeedRecord.toEntity() = AssessmentItemSnapshotEntity(
    assessmentItemSnapshotId = assessmentItemSnapshotId,
    itemRevision = itemRevision,
    practiceUnitId = practiceUnitId,
    problemRevisionId = problemRevisionId,
    tutorContentSnapshotId = tutorContentSnapshotId,
    promptMarkdown = promptMarkdown,
    optionsSnapshot = optionsSnapshot,
    answerSpecSnapshot = answerSpecSnapshot,
    verificationStatus = verificationStatus,
    assessmentEligibility = assessmentEligibility,
    scoringMode = scoringMode,
    learnerSnapshotVersion = learnerSnapshotVersion,
    projectionCheckpoint = projectionCheckpoint,
    hintLevelAtPresentation = hintLevelAtPresentation,
    answerRevealState = answerRevealState,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun AssessmentItemSnapshotEntity.toRecord() = AssessmentItemSnapshotSeedRecord(
    assessmentItemSnapshotId = assessmentItemSnapshotId,
    itemRevision = itemRevision,
    practiceUnitId = practiceUnitId,
    problemRevisionId = problemRevisionId,
    tutorContentSnapshotId = tutorContentSnapshotId,
    promptMarkdown = promptMarkdown,
    optionsSnapshot = optionsSnapshot,
    answerSpecSnapshot = answerSpecSnapshot,
    verificationStatus = verificationStatus,
    assessmentEligibility = assessmentEligibility,
    scoringMode = scoringMode,
    learnerSnapshotVersion = learnerSnapshotVersion,
    projectionCheckpoint = projectionCheckpoint,
    hintLevelAtPresentation = hintLevelAtPresentation,
    answerRevealState = answerRevealState,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun AssessmentEventSeedRecord.toEntity() = AssessmentEventEntity(
    assessmentEventId = assessmentEventId,
    assessmentItemSnapshotId = assessmentItemSnapshotId,
    eventSequence = eventSequence,
    eventType = eventType,
    hintLevel = hintLevel,
    submittedResponse = submittedResponse,
    occurredAtEpochMillis = occurredAtEpochMillis,
)

private fun ProblemMemoryStateRecord.toEntity() = ProblemMemoryStateEntity(
    practiceUnitId = practiceUnitId,
    stabilityDays = stabilityDays,
    difficulty = difficulty,
    lastReviewedAtEpochMillis = lastReviewedAtEpochMillis,
    nextReviewAtEpochMillis = nextReviewAtEpochMillis,
    reviewCount = reviewCount,
    lapseCount = lapseCount,
    retrievability = retrievability,
    projectionCheckpoint = projectionCheckpoint,
    projectorVersion = projectorVersion,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun KnowledgeMasteryStateRecord.toEntity() = KnowledgeMasteryStateEntity(
    knowledgeNodeId = knowledgeNodeId,
    masteryProbability = masteryProbability,
    independentCorrectCount = independentCorrectCount,
    assistedCorrectCount = assistedCorrectCount,
    incorrectCount = incorrectCount,
    evidenceWeightTotal = evidenceWeightTotal,
    lastEvidenceAtEpochMillis = lastEvidenceAtEpochMillis,
    projectionCheckpoint = projectionCheckpoint,
    projectorVersion = projectorVersion,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun ReviewPlanRecord.toEntity() = ReviewPlanEntity(
    reviewPlanId = reviewPlanId,
    learnerId = learnerId,
    localDate = localDate,
    localDayEpochDay = localDayEpochDay,
    timeZoneId = timeZoneId,
    timeBudgetSeconds = timeBudgetSeconds,
    planningAtEpochMillis = planningAtEpochMillis,
    status = status,
    plannerVersion = plannerVersion,
    projectionCheckpoint = projectionCheckpoint,
    inputFingerprint = inputFingerprint,
    planFingerprint = planFingerprint,
    planRevision = planRevision,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun ReviewQueueItemRecord.toEntity() = ReviewQueueItemEntity(
    reviewQueueItemId = reviewQueueItemId,
    reviewPlanId = reviewPlanId,
    practiceUnitId = practiceUnitId,
    itemFamilyId = itemFamilyId,
    sourceBundleId = sourceBundleId,
    ordinal = ordinal,
    priorityScore = priorityScore,
    difficultyBand = difficultyBand,
    dueAtEpochMillis = dueAtEpochMillis,
    estimatedSeconds = estimatedSeconds,
    reasonSnapshot = reasonSnapshot,
    status = status,
)

private fun ReviewQueueItemRecord.toKnowledgeNodeEntities() = knowledgeNodeIds
    .sorted()
    .map { ReviewQueueKnowledgeNodeEntity(reviewQueueItemId, it) }

private fun ReviewQueueItemRecord.toReasonEntities() = reasons
    .sorted()
    .map { ReviewQueueReasonEntity(reviewQueueItemId, it) }

private fun ReviewSessionRecord.toEntity() = ReviewSessionEntity(
    reviewSessionId = reviewSessionId,
    reviewPlanId = reviewPlanId,
    status = status,
    activeSessionKey = reviewPlanId.takeIf { status == StudyDbValue.ReviewStatus.IN_PROGRESS },
    startedAtEpochMillis = startedAtEpochMillis,
    lastActiveAtEpochMillis = lastActiveAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    currentOrdinal = currentOrdinal,
    timeBudgetSeconds = timeBudgetSeconds,
    projectionCheckpoint = projectionCheckpoint,
    stateVersion = stateVersion,
)

private fun ReviewSessionRecord.toRevisionEntity() = ReviewSessionRevisionEntity(
    reviewSessionId = reviewSessionId,
    stateVersion = stateVersion,
    reviewPlanId = reviewPlanId,
    status = status,
    startedAtEpochMillis = startedAtEpochMillis,
    lastActiveAtEpochMillis = lastActiveAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    currentOrdinal = currentOrdinal,
    timeBudgetSeconds = timeBudgetSeconds,
    projectionCheckpoint = projectionCheckpoint,
)

private fun ReviewPlanAggregate.toRecord(): ReviewPlanBundle {
    val sortedQueue = queue.sortedBy { it.item.ordinal }
    return ReviewPlanBundle(
        plan = ReviewPlanRecord(
            reviewPlanId = plan.reviewPlanId,
            learnerId = plan.learnerId,
            localDate = plan.localDate,
            localDayEpochDay = plan.localDayEpochDay,
            timeZoneId = plan.timeZoneId,
            timeBudgetSeconds = plan.timeBudgetSeconds,
            planningAtEpochMillis = plan.planningAtEpochMillis,
            status = plan.status,
            plannerVersion = plan.plannerVersion,
            projectionCheckpoint = plan.projectionCheckpoint,
            inputFingerprint = plan.inputFingerprint,
            planFingerprint = plan.planFingerprint,
            planRevision = plan.planRevision,
            createdAtEpochMillis = plan.createdAtEpochMillis,
        ),
        queue = sortedQueue.map { aggregate ->
            val item = aggregate.item
            ReviewQueueItemRecord(
                reviewQueueItemId = item.reviewQueueItemId,
                reviewPlanId = item.reviewPlanId,
                practiceUnitId = item.practiceUnitId,
                knowledgeNodeIds = aggregate.knowledgeNodes.mapTo(linkedSetOf()) { it.knowledgeNodeId },
                itemFamilyId = item.itemFamilyId,
                sourceBundleId = item.sourceBundleId,
                reasons = aggregate.reasons.mapTo(linkedSetOf()) { it.reason },
                ordinal = item.ordinal,
                priorityScore = item.priorityScore,
                difficultyBand = item.difficultyBand,
                dueAtEpochMillis = item.dueAtEpochMillis,
                estimatedSeconds = item.estimatedSeconds,
                reasonSnapshot = item.reasonSnapshot,
                status = item.status,
            )
        },
        activeSession = activeSessionHead()?.toRecord(),
        isCurrent = currentSlots.isNotEmpty(),
        latestSession = latestSessionHead()?.toRecord(),
    )
}

private fun ReviewSessionEntity.toRecord() = ReviewSessionRecord(
    reviewSessionId = reviewSessionId,
    reviewPlanId = reviewPlanId,
    status = status,
    startedAtEpochMillis = startedAtEpochMillis,
    lastActiveAtEpochMillis = lastActiveAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    currentOrdinal = currentOrdinal,
    timeBudgetSeconds = timeBudgetSeconds,
    projectionCheckpoint = projectionCheckpoint,
    stateVersion = stateVersion,
)

private fun ProblemDraftEditWorkspaceRecord.toConfirmedRevision(
    expected: ExpectedProblemDraftEditWorkspace,
): ProblemDraftRevisionRecord {
    val workspace = try {
        DatabaseContractValidator.decodeProblemDraftEditWorkspace(
            snapshotSchemaVersion = snapshotSchemaVersion,
            workspaceSnapshot = workspaceSnapshot,
            workspaceFingerprint = workspaceFingerprint,
        )
    } catch (failure: Exception) {
        throw ProblemDraftEditWorkspaceIntegrityException(
            "Problem-draft workspace $draftId is corrupted",
            failure,
        )
    }
    val finalRequest = workspace.finalConfirmationRequest
        ?: throw ProblemDraftEditWorkspaceConflictException(
            "Problem-draft workspace $draftId has no final confirmation identity",
        )
    if (
        draftId != expected.draftId ||
        basisRevisionNumber != expected.basisRevisionNumber ||
        workspaceVersion != expected.workspaceVersion ||
        workspaceFingerprint != expected.workspaceFingerprint ||
        finalRequest.requestId != expected.finalRequestId ||
        finalRequest.occurredAtEpochMillis != expected.finalOccurredAtEpochMillis ||
        expected.finalOccurredAtEpochMillis < updatedAtEpochMillis
    ) {
        throw ProblemDraftEditWorkspaceConflictException(
            "Problem-draft workspace $draftId does not match the final request",
        )
    }
    val subject = workspace.subject
        ?: throw ProblemDraftEditWorkspaceConflictException(
            "Problem-draft workspace $draftId has no confirmed subject",
        )
    val title = workspace.workingDocument.document.title
        ?.takeIf(String::isNotBlank)
        ?: throw ProblemDraftEditWorkspaceConflictException(
            "Problem-draft workspace $draftId has no confirmed title",
        )
    if (CapturedQuestionDocumentValidator.validateForCommit(workspace.workingDocument).isNotEmpty()) {
        throw ProblemDraftEditWorkspaceConflictException(
            "Problem-draft workspace $draftId is not ready to confirm",
        )
    }
    return ProblemDraftRevisionRecord(
        draftId = draftId,
        revisionNumber = basisRevisionNumber + 1,
        basisRevisionNumber = basisRevisionNumber,
        subject = subject,
        title = title,
        questionDocument = workspace.workingDocument,
        documentFingerprint = CapturedQuestionDocumentFingerprint.of(workspace.workingDocument),
        author = StudyDbValue.ProblemDraftAuthor.USER,
        createdAtEpochMillis = expected.finalOccurredAtEpochMillis,
    )
}

private fun ExpectedProblemDraftEditWorkspace.toConsumeCommand() =
    ConsumeProblemDraftEditWorkspaceCommand(
        draftId = draftId,
        basisRevisionNumber = basisRevisionNumber,
        expectedWorkspaceVersion = workspaceVersion,
        expectedWorkspaceFingerprint = workspaceFingerprint,
    )

private fun ProblemDraftEditWorkspaceRecord.toConsumeCommand() =
    ConsumeProblemDraftEditWorkspaceCommand(
        draftId = draftId,
        basisRevisionNumber = basisRevisionNumber,
        expectedWorkspaceVersion = workspaceVersion,
        expectedWorkspaceFingerprint = workspaceFingerprint,
    )

private fun ReviewSessionAdvanceReceiptEntity.toRecord() = ReviewSessionAdvanceReceipt(
    sessionId = reviewSessionId,
    fromVersion = fromVersion,
    toVersion = toVersion,
    reviewQueueItemId = reviewQueueItemId,
    practiceUnitId = practiceUnitId,
    attemptId = attemptId,
    submissionId = submissionId,
    presentationId = presentationId,
    occurredAtEpochMillis = occurredAtEpochMillis,
)

private fun MistakeRow.toRecord() = MistakeRecord(
    entryId = entryId,
    problemId = problemId,
    problemRevisionId = problemRevisionId,
    practiceUnitId = practiceUnitId,
    sourceKey = sourceKey,
    subject = subject,
    title = title,
    problemMarkdown = problemMarkdown,
    status = status,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    nextReviewAtEpochMillis = nextReviewAtEpochMillis,
    retrievability = retrievability,
    estimatedSeconds = estimatedSeconds,
    knowledgeNodeIds = knowledgeNodeIds.toCatalogLabels().toCollection(linkedSetOf()),
    chapterLabels = chapterLabels.toCatalogLabels(),
    knowledgeLabels = knowledgeLabels.toCatalogLabels(),
    captureOccurrenceCount = maxOf(1, captureOccurrenceCount),
)

private fun LibraryCatalogView.toRow() = LibraryCatalogRow(
    entryId = entryId,
    title = title,
    problemMarkdown = problemMarkdown,
    subject = subject,
    chapterLabels = chapterLabels.toCatalogLabels(),
    knowledgeLabels = knowledgeLabels.toCatalogLabels(),
    masteryId = masteryId,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    nextReviewAtEpochMillis = nextReviewAtEpochMillis,
    retrievability = retrievability,
)

private fun CanonicalSourceAssetRow.toRecord() = CanonicalSourceAssetRecord(
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

private fun LibraryFacetCountRow.toRecord() = LibraryFacetCountRecord(
    id = id,
    label = label,
    count = count,
)

private fun String?.toCatalogLabels(): List<String> = this
    ?.split("\u001F")
    ?.map(String::trim)
    ?.filter(String::isNotEmpty)
    ?.distinct()
    ?.sorted()
    .orEmpty()

private fun LibraryFtsSearchDao.LibrarySearchHitRow.toCatalogRow() = LibraryCatalogRow(
    entryId = entryId,
    title = title,
    problemMarkdown = problemMarkdown,
    subject = subject,
    chapterLabels = chapterLabels.toCatalogLabels(),
    knowledgeLabels = knowledgeLabels.toCatalogLabels(),
    masteryId = masteryId,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    nextReviewAtEpochMillis = nextReviewAtEpochMillis,
    retrievability = retrievability,
)

private class RefreshingPagingSource<T : Any>(
    private val beforeLoad: suspend () -> Unit,
    private val delegate: PagingSource<Int, T>,
) : PagingSource<Int, T>() {
    override fun getRefreshKey(state: androidx.paging.PagingState<Int, T>): Int? {
        return delegate.getRefreshKey(state)
    }
    override suspend fun load(params: PagingSource.LoadParams<Int>): PagingSource.LoadResult<Int, T> {
        try {
            beforeLoad()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Projection refresh is best-effort; delegate load still proceeds.
        }
        return delegate.load(params)
    }
}
