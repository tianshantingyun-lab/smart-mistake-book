package com.tingyun.smartmistakebook.core.student.mistake.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionCandidate
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionResolutionStatus
import com.tingyun.smartmistakebook.core.model.ProblemErrorEvidenceKind
import com.tingyun.smartmistakebook.core.model.ProblemErrorEvidenceRef
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudentMistakeAuthorityInstrumentedTest {
    @Test
    fun versionOneMigratesThroughVersionThreeAndPreservesLegacyRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = testDatabaseName("v1-v2")
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 1)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use(::insertLegacyProblem)

            StudentMistakeStoreFactory.openForTest(context, databaseName).use { store ->
                val legacy = checkNotNull(store.findProblem(problemRef("legacy")))
                assertEquals("旧题干", legacy.stemMarkdown)
                assertNull(legacy.errorBookEntryId)
                assertNull(legacy.capturedQuestionDocument)
                assertTrue(legacy.originalImages.isEmpty())
            }
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(STUDENT_MISTAKE_DATABASE_VERSION, database.version)
                assertTrue(database.hasColumn("student_problem_document", "error_book_entry_id"))
                assertTrue(
                    database.hasColumn(
                        "student_problem_revision",
                        "captured_question_document_wire",
                    ),
                )
                assertTrue(database.hasTable("student_problem_solution_analysis"))
                assertTrue(database.hasTable("student_mistake_migration_receipt"))
                assertTrue(database.hasTable("student_review_self_report_receipt"))
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun exactDetailRoundTripsAndChangeVersionIsObservable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = testDatabaseName("detail")
        context.deleteDatabase(databaseName)
        try {
            StudentMistakeStoreFactory.openForTest(context, databaseName).use { store ->
                val command = authorityCommand(problemSuffix = "detail")
                val saved =
                    store.saveConfirmedMistake(
                        SaveIntentConfirmedStudentMistakeCommand(
                            problem = command,
                            intentConfirmationId = "intent-detail",
                            intentCanonicalFingerprint = "9".repeat(64),
                            confirmedAtEpochMillis = 90,
                            favorite = true,
                        ),
                    )
                val replayed =
                    store.saveConfirmedMistake(
                        SaveIntentConfirmedStudentMistakeCommand(
                            problem = command,
                            intentConfirmationId = "intent-detail",
                            intentCanonicalFingerprint = "9".repeat(64),
                            confirmedAtEpochMillis = 90,
                            favorite = true,
                        ),
                    )
                assertEquals(saved, replayed)
                assertEquals(
                    saved,
                    store.readMistakeDetail(
                        StudentMistakeDetailQuery(
                            learnerId = command.revision.problem.learnerId,
                            problem = command.revision.problem,
                            errorBookEntryId = checkNotNull(command.errorBookEntryId),
                        ),
                    ),
                )
                assertEquals(command.capturedQuestionDocument, saved.capturedQuestionDocument)
                assertEquals(command.solutionAnalysis, saved.solutionAnalysis)
                assertEquals(command.errorAttributions, saved.errorAttributions)
                assertEquals(command.originalImages, saved.originalImages)
                val initialCandidate =
                    store.readReviewCandidates(
                        StudentReviewCandidateQuery(
                            learnerId = LEARNER_ID,
                            nowEpochMillis = command.committedAtEpochMillis,
                        ),
                    ).items.single()
                assertEquals(command.revision, initialCandidate.problemRevision)
                assertEquals(setOf("saved-mistake"), initialCandidate.reasonCodes)
                assertTrue(store.observeChangeVersion(LEARNER_ID).first() > 0)

                val conflicting =
                    authorityCommand(problemSuffix = "detail-conflict").copy(
                        errorBookEntryId = command.errorBookEntryId,
                    )
                assertTrue(
                    runCatching {
                        store.saveConfirmedMistake(
                            SaveIntentConfirmedStudentMistakeCommand(
                                problem = conflicting,
                                intentConfirmationId = "intent-detail-conflict",
                                intentCanonicalFingerprint = "6".repeat(64),
                                confirmedAtEpochMillis = 90,
                            ),
                        )
                    }.isFailure,
                )
                assertNull(store.findProblem(conflicting.revision.problem))
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun targetConfirmedSaveDeduplicatesDifferentRequestsAndRejectsMismatch() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = testDatabaseName("target-dedup")
        context.deleteDatabase(databaseName)
        val problem = authorityCommand(problemSuffix = "target-dedup")
        try {
            StudentMistakeStoreFactory.openForTest(context, databaseName).use { store ->
                val first =
                    store.saveConfirmedMistake(
                        SaveTargetConfirmedStudentMistakeCommand(
                            problem = problem,
                            confirmedAtEpochMillis = 80,
                        ),
                    )
                val replayedFromAnotherRequest =
                    store.saveConfirmedMistake(
                        SaveTargetConfirmedStudentMistakeCommand(
                            problem = problem,
                            confirmedAtEpochMillis = 90,
                        ),
                    )

                assertEquals(TargetConfirmedStudentMistakeSaveOutcome.CREATED, first.outcome)
                assertEquals(
                    TargetConfirmedStudentMistakeSaveOutcome.DUPLICATE,
                    replayedFromAnotherRequest.outcome,
                )
                assertEquals(first.problemRevision, replayedFromAnotherRequest.problemRevision)
                assertEquals(first.errorBookEntryId, replayedFromAnotherRequest.errorBookEntryId)
                assertEquals(
                    first.targetCanonicalFingerprint,
                    replayedFromAnotherRequest.targetCanonicalFingerprint,
                )
                assertTrue(
                    runCatching {
                        store.saveConfirmedMistake(
                            SaveTargetConfirmedStudentMistakeCommand(
                                problem = problem,
                                confirmedAtEpochMillis = 90,
                                favorite = true,
                            ),
                        )
                    }.isFailure,
                )
                listOf(
                    problem.copy(title = "Changed immutable title"),
                    problem.copy(
                        originalImages =
                            problem.originalImages.map { image ->
                                image.copy(
                                    localContentUri =
                                        "${image.localContentUri}-changed",
                                )
                            },
                    ),
                ).forEach { mismatchedProblem ->
                    assertTrue(
                        runCatching {
                            store.saveConfirmedMistake(
                                SaveTargetConfirmedStudentMistakeCommand(
                                    problem = mismatchedProblem,
                                    confirmedAtEpochMillis = 90,
                                ),
                            )
                        }.isFailure,
                    )
                }
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(
                    1,
                    database.rowCount(
                        tableName = "student_mistake_save_receipt",
                        columnName = "basis_revision_id",
                        value = problem.revision.revisionId,
                    ),
                )
                assertEquals(
                    1,
                    database.rowCount(
                        tableName = "student_problem_document",
                        columnName = "problem_id",
                        value = problem.revision.problem.problemId,
                    ),
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun detailAndHistoryRequireAuthenticatedLearnerProblemAndEntryToAgree() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = testDatabaseName("detail-scope")
        context.deleteDatabase(databaseName)
        try {
            StudentMistakeStoreFactory.openForTest(context, databaseName).use { store ->
                val owner = authorityCommand(problemSuffix = "scope-owner")
                val other =
                    authorityCommand(
                        problemSuffix = "scope-other",
                        learnerId = OTHER_LEARNER_ID,
                    )
                listOf(owner, other).forEach { command ->
                    store.saveConfirmedMistake(
                        SaveIntentConfirmedStudentMistakeCommand(
                            problem = command,
                            intentConfirmationId =
                                "intent-${command.revision.problem.problemId}",
                            intentCanonicalFingerprint =
                                testIntentFingerprint(
                                    intentId = "intent-${command.revision.problem.problemId}",
                                    target = command.revision,
                                ),
                            confirmedAtEpochMillis = 90,
                        ),
                    )
                }

                val ownerQuery =
                    StudentMistakeDetailQuery(
                        learnerId = LEARNER_ID,
                        problem = owner.revision.problem,
                        errorBookEntryId = checkNotNull(owner.errorBookEntryId),
                    )
                assertEquals(owner.revision, checkNotNull(store.readMistakeDetail(ownerQuery)).revision)

                val crossLearnerEntryQuery =
                    ownerQuery.copy(errorBookEntryId = checkNotNull(other.errorBookEntryId))
                assertNull(store.readMistakeDetail(crossLearnerEntryQuery))
                assertTrue(
                    store.readRevisionHistory(
                        StudentProblemRevisionHistoryQuery(
                            learnerId = crossLearnerEntryQuery.learnerId,
                            problem = crossLearnerEntryQuery.problem,
                            errorBookEntryId = crossLearnerEntryQuery.errorBookEntryId,
                        ),
                    ).items.isEmpty(),
                )
                assertTrue(
                    runCatching {
                        StudentMistakeDetailQuery(
                            learnerId = LEARNER_ID,
                            problem = other.revision.problem,
                            errorBookEntryId = checkNotNull(other.errorBookEntryId),
                        )
                    }.isFailure,
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun modelReadPortCannotOverrideItsOwnerLearner() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = testDatabaseName("model-read-scope")
        context.deleteDatabase(databaseName)
        try {
            val owner = authorityCommand(problemSuffix = "model-owner")
            val other =
                authorityCommand(
                    problemSuffix = "model-other",
                    learnerId = OTHER_LEARNER_ID,
                )
            StudentMistakeStoreFactory.openForTest(context, databaseName).use { store ->
                listOf(owner, other).forEach { command ->
                    store.saveConfirmedMistake(
                        SaveIntentConfirmedStudentMistakeCommand(
                            problem = command,
                            intentConfirmationId =
                                "intent-${command.revision.problem.problemId}",
                            intentCanonicalFingerprint =
                                testIntentFingerprint(
                                    intentId = "intent-${command.revision.problem.problemId}",
                                    target = command.revision,
                                ),
                            confirmedAtEpochMillis = 90,
                        ),
                    )
                }
            }

            StudentMistakeModelReadPortFactory.openForTest(
                context = context,
                databaseName = databaseName,
                learnerId = LEARNER_ID,
            ).use { port ->
                val summaries =
                    port.readProblemSummaries(
                        StudentMistakeModelReadQuery(subject = SubjectKind.MATH),
                    )

                assertEquals(listOf(owner.revision), summaries.map { it.problemRevision })
                assertTrue(summaries.none { it.problemRevision.problem.learnerId == OTHER_LEARNER_ID })
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun migrationPageReplayIsIdempotentAndCheckpointed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = testDatabaseName("cutover")
        context.deleteDatabase(databaseName)
        try {
            val command = authorityCommand(problemSuffix = "cutover")
            val page =
                ApplyStudentMistakeMigrationPageCommand(
                    migrationId = "legacy-cutover-v1",
                    sourceDatabaseCanonicalFingerprint = "a".repeat(64),
                    sourcePageCanonicalFingerprint = "b".repeat(64),
                    expectedCheckpointCanonicalFingerprint = null,
                    afterExclusive = null,
                    records =
                        listOf(
                            StudentMistakeMigrationRecord(
                                problem = command,
                                collection =
                                    SetStudentProblemCollectionCommand(
                                        problem = command.revision.problem,
                                        mistakeState = StudentMistakeEntryState.ACTIVE,
                                        favorite = false,
                                        changedAtEpochMillis = command.committedAtEpochMillis,
                                    ),
                            ),
                        ),
                    isLastPage = true,
                    appliedAtEpochMillis = 200,
                )
            StudentMistakeMigrationPortFactory.openForTest(context, databaseName).use { port ->
                val first = port.applyPage(page)
                val replay = port.applyPage(page)
                assertEquals(first, replay)
                assertEquals(1L, checkNotNull(port.readCheckpoint(page.migrationId)).importedRecordCount)
                assertTrue(checkNotNull(port.readCheckpoint(page.migrationId)).completed)
            }
            StudentMistakeStoreFactory.openForTest(context, databaseName).use { store ->
                assertEquals(
                    command.revision,
                    checkNotNull(
                        store.readMistakeDetail(
                            StudentMistakeDetailQuery(
                                learnerId = command.revision.problem.learnerId,
                                problem = command.revision.problem,
                                errorBookEntryId = checkNotNull(command.errorBookEntryId),
                            ),
                        ),
                    ).revision,
                )
                assertEquals(
                    listOf(command.revision),
                    store.readReviewCandidates(
                        StudentReviewCandidateQuery(
                            learnerId = LEARNER_ID,
                            nowEpochMillis = command.committedAtEpochMillis,
                        ),
                    ).items.map(StudentReviewCandidate::problemRevision),
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun corruptedCapturedSnapshotFailsClosed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = testDatabaseName("corrupt")
        context.deleteDatabase(databaseName)
        try {
            val command = authorityCommand(problemSuffix = "corrupt")
            StudentMistakeStoreFactory.openForTest(context, databaseName).use { store ->
                store.saveConfirmedMistake(
                    SaveIntentConfirmedStudentMistakeCommand(
                        problem = command,
                        intentConfirmationId = "intent-corrupt",
                        intentCanonicalFingerprint = "8".repeat(64),
                        confirmedAtEpochMillis = 90,
                    ),
                )
            }
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                database.execSQL(
                    """
                    UPDATE student_problem_revision
                    SET captured_question_document_wire = ?
                    WHERE revision_id = ?
                    """.trimIndent(),
                    arrayOf("{}", command.revision.revisionId),
                )
            }
            StudentMistakeStoreFactory.openForTest(context, databaseName).use { store ->
                assertTrue(
                    runCatching {
                        store.readMistakeDetail(
                            StudentMistakeDetailQuery(
                                learnerId = command.revision.problem.learnerId,
                                problem = command.revision.problem,
                                errorBookEntryId = checkNotNull(command.errorBookEntryId),
                            ),
                        )
                    }.isFailure,
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun revisionHistoryUsesBoundedKeysetPagination() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = testDatabaseName("history")
        context.deleteDatabase(databaseName)
        try {
            StudentMistakeStoreFactory.openForTest(context, databaseName).use { store ->
                val first = authorityCommand(problemSuffix = "history")
                store.saveConfirmedMistake(
                    SaveIntentConfirmedStudentMistakeCommand(
                        problem = first,
                        intentConfirmationId = "intent-history",
                        intentCanonicalFingerprint = "7".repeat(64),
                        confirmedAtEpochMillis = 90,
                    ),
                )
                val second =
                    authorityCommand(
                        problemSuffix = "history",
                        revisionNumber = 2,
                        revisionSuffix = "history-2",
                        committedAtEpochMillis = 200,
                        includeAnalysis = false,
                    )
                store.commitProblem(second)
                val firstPage =
                    store.readRevisionHistory(
                        StudentProblemRevisionHistoryQuery(
                            learnerId = first.revision.problem.learnerId,
                            problem = first.revision.problem,
                            errorBookEntryId = checkNotNull(first.errorBookEntryId),
                            limit = 1,
                        ),
                    )
                assertEquals(listOf(2), firstPage.items.map { it.revision.revisionNumber })
                val secondPage =
                    store.readRevisionHistory(
                        StudentProblemRevisionHistoryQuery(
                            learnerId = first.revision.problem.learnerId,
                            problem = first.revision.problem,
                            errorBookEntryId = checkNotNull(first.errorBookEntryId),
                            cursor = checkNotNull(firstPage.nextCursor),
                            limit = 1,
                        ),
                    )
                assertEquals(listOf(1), secondPage.items.map { it.revision.revisionNumber })
                assertNull(secondPage.nextCursor)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun authorityCommand(
        problemSuffix: String,
        learnerId: String = LEARNER_ID,
        revisionNumber: Int = 1,
        revisionSuffix: String = problemSuffix,
        committedAtEpochMillis: Long = 100,
        includeAnalysis: Boolean = true,
    ): CommitStudentProblemCommand {
        val document = capturedDocument(revisionSuffix)
        val problem = problemRef(problemSuffix, learnerId)
        val revision =
            StudentProblemRevisionRef(
                problem = problem,
                revisionId = "revision-$revisionSuffix",
                revisionNumber = revisionNumber,
                documentCanonicalFingerprint = CapturedQuestionDocumentFingerprint.of(document),
            )
        val solution =
            if (includeAnalysis) {
                StudentProblemSolutionAnalysis(
                    solutionAnalysisId = "solution-$revisionSuffix",
                    problemRevision = revision,
                    summaryMarkdown = "先整理条件，再完成计算。",
                    finalAnswerMarkdown = "结果为 1。",
                    steps =
                        listOf(
                            StudentProblemSolutionStep(
                                stepId = "step-$revisionSuffix",
                                ordinal = 1,
                                summaryMarkdown = "整理已知条件。",
                                reasoningMarkdown = "将已知量代入表达式。",
                                resultMarkdown = "得到中间结果。",
                                stepCanonicalFingerprint = "2".repeat(64),
                            ),
                        ),
                    modelProviderId = "provider-test",
                    modelId = "model-test",
                    analyzerVersion = "analyzer-v1",
                    resultCanonicalFingerprint = "3".repeat(64),
                    recordedAtEpochMillis = committedAtEpochMillis,
                )
            } else {
                null
            }
        val attributions =
            if (solution == null) {
                emptyList()
            } else {
                listOf(
                    StudentProblemErrorAttribution(
                        attributionId = "attribution-$revisionSuffix",
                        problemRevision = revision,
                        solutionAnalysisId = solution.solutionAnalysisId,
                        candidate =
                            ProblemErrorAttributionCandidate(
                                resolutionStatus =
                                    ProblemErrorAttributionResolutionStatus.RESOLVED,
                                rationaleMarkdown = "在代入条件时出现符号错误。",
                                confidence = 0.9,
                                stepOrdinal = 1,
                                atomicReferenceId = "atom-sign",
                                evidenceRefs =
                                    listOf(
                                        ProblemErrorEvidenceRef(
                                            blockId = "stem-$revisionSuffix",
                                            sourceAssetId = "asset-$revisionSuffix",
                                            evidenceKind = ProblemErrorEvidenceKind.STUDENT_WORK,
                                        ),
                                    ),
                            ),
                        modelProviderId = "provider-test",
                        modelId = "model-test",
                        analyzerVersion = "analyzer-v1",
                        resultCanonicalFingerprint = "4".repeat(64),
                        recordedAtEpochMillis = committedAtEpochMillis,
                    ),
                )
            }
        return CommitStudentProblemCommand(
            revision = revision,
            title = "函数题",
            stemMarkdown = "求函数值。",
            practiceUnitKind = StudentPracticeUnitKind.WHOLE_PROBLEM,
            practiceUnitTitle = "函数题",
            itemFamilyId = "family-$problemSuffix",
            estimatedDurationSeconds = 120,
            sourceBundleId = "bundle-$problemSuffix",
            partIds = emptyList(),
            originalImages =
                listOf(
                    StudentProblemImageReference(
                        imageReferenceId = "image-$revisionSuffix",
                        localContentUri = "content://student-mistakes/$revisionSuffix",
                        contentCanonicalFingerprint = "5".repeat(64),
                        mediaType = "image/jpeg",
                        ordinal = 0,
                        widthPixels = 1_200,
                        heightPixels = 800,
                        byteSize = 42_000,
                        selectedRegions =
                            listOf(
                                NormalizedSourceRegion(
                                    left = 0.1,
                                    top = 0.2,
                                    right = 0.9,
                                    bottom = 0.8,
                                ),
                            ),
                    ),
                ),
            committedAtEpochMillis = committedAtEpochMillis,
            errorBookEntryId = "entry-$problemSuffix",
            capturedQuestionDocument = document,
            solutionAnalysis = solution,
            errorAttributions = attributions,
        )
    }

    private fun capturedDocument(suffix: String): CapturedQuestionDocument =
        CapturedQuestionDocument(
            document =
                QuestionDocument(
                    id = "document-$suffix",
                    title = "函数",
                    blocks =
                        listOf(
                            ContentBlock.Paragraph(
                                id = "stem-$suffix",
                                markdown = "求函数值。",
                            ),
                        ),
                ),
            blockEvidence =
                listOf(
                    QuestionBlockEvidence(
                        blockId = "stem-$suffix",
                        sourceAssetId = "asset-$suffix",
                        sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                        writingLayer = WritingLayer.PRINTED,
                        provenance = QuestionBlockProvenance.USER_TRANSCRIPTION,
                        reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                    ),
                ),
        )

    private fun problemRef(
        suffix: String,
        learnerId: String = LEARNER_ID,
    ): StudentProblemRef =
        StudentProblemRef(
            learnerId = learnerId,
            subject = SubjectKind.MATH,
            problemId = "problem-$suffix",
            practiceUnitId = "unit-$suffix",
        )

    private fun insertLegacyProblem(database: SQLiteDatabase) {
        database.execSQL(
            """
            INSERT INTO student_problem_document(
                problem_id, learner_id, subject, primary_practice_unit_id,
                current_revision_id, lifecycle_state, archived_at_epoch_millis,
                tombstoned_at_epoch_millis, created_at_epoch_millis,
                updated_at_epoch_millis
            ) VALUES(?, ?, ?, ?, ?, ?, NULL, NULL, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "problem-legacy",
                LEARNER_ID,
                SubjectKind.MATH.name,
                "unit-legacy",
                "revision-legacy",
                StudentProblemLifecycleState.ACTIVE.name,
                1L,
                1L,
            ),
        )
        database.execSQL(
            """
            INSERT INTO student_problem_revision(
                revision_id, problem_id, revision_number, title, stem_markdown,
                document_canonical_fingerprint, created_at_epoch_millis,
                updated_at_epoch_millis
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "revision-legacy",
                "problem-legacy",
                1,
                "旧题",
                "旧题干",
                "1".repeat(64),
                1L,
                1L,
            ),
        )
        database.execSQL(
            """
            INSERT INTO student_practice_unit(
                practice_unit_id, problem_id, basis_revision_id, unit_kind,
                title, item_family_id, estimated_duration_seconds,
                source_bundle_id, part_ids_wire, created_at_epoch_millis,
                updated_at_epoch_millis
            ) VALUES(?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                "unit-legacy",
                "problem-legacy",
                "revision-legacy",
                StudentPracticeUnitKind.WHOLE_PROBLEM.name,
                "旧题",
                "family-legacy",
                60,
                "0:",
                1L,
                1L,
            ),
        )
    }

    private fun createDatabaseFromExportedSchema(
        context: Context,
        databaseName: String,
        version: Int,
    ) {
        val assetPath =
            "com.tingyun.smartmistakebook.core.student.mistake.database." +
                "StudentMistakeRoomDatabase/$version.json"
        val schema =
            context.assets.open(assetPath).bufferedReader().use { reader ->
                JSONObject(reader.readText()).getJSONObject("database")
            }
        val database =
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null)
        try {
            val entities = schema.getJSONArray("entities")
            repeat(entities.length()) { index ->
                val entity = entities.getJSONObject(index)
                val tableName = entity.getString("tableName")
                database.execSQL(
                    entity.getString("createSql").replace("${'$'}{TABLE_NAME}", tableName),
                )
                entity.optJSONArray("indices")?.let { indices ->
                    repeat(indices.length()) { position ->
                        database.execSQL(
                            indices.getJSONObject(position)
                                .getString("createSql")
                                .replace("${'$'}{TABLE_NAME}", tableName),
                        )
                    }
                }
            }
            val setupQueries = schema.getJSONArray("setupQueries")
            repeat(setupQueries.length()) { index ->
                database.execSQL(setupQueries.getString(index))
            }
            database.version = version
        } finally {
            database.close()
        }
    }

    private fun SQLiteDatabase.hasColumn(
        tableName: String,
        columnName: String,
    ): Boolean =
        rawQuery("PRAGMA table_info(`$tableName`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            generateSequence { cursor.takeIf { it.moveToNext() } }
                .any { cursor.getString(nameIndex) == columnName }
        }

    private fun SQLiteDatabase.hasTable(tableName: String): Boolean =
        rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(tableName),
        ).use { it.moveToFirst() }

    private fun SQLiteDatabase.rowCount(
        tableName: String,
        columnName: String,
        value: String,
    ): Int =
        rawQuery(
            "SELECT COUNT(*) FROM `$tableName` WHERE `$columnName` = ?",
            arrayOf(value),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun testIntentFingerprint(
        intentId: String,
        target: StudentProblemRevisionRef,
    ): String =
        CanonicalSha256("student-mistake-authority-test-intent-v1")
            .field("intentId", intentId)
            .field("targetRevision", target.canonicalFingerprint)
            .finish()

    private fun testDatabaseName(suffix: String): String =
        "student-authority-$suffix-${System.nanoTime()}" +
            StudentMistakeStoreFactory.TEST_DATABASE_SUFFIX

    private companion object {
        const val LEARNER_ID = "learner-authority"
        const val OTHER_LEARNER_ID = "learner-authority-other"
    }
}
