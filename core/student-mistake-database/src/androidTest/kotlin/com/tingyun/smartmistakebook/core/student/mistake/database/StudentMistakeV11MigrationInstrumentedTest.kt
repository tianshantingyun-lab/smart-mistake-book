package com.tingyun.smartmistakebook.core.student.mistake.database

import android.database.sqlite.SQLiteDatabase
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionResolutionStatus
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudentMistakeV11MigrationInstrumentedTest {
    @Test
    fun migration10To11AddsAnEmptyAppendOnlyIdentityReceiptLedger() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val databaseName = "student-mistake-v10-v11-receipt.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        try {
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = context.getDatabasePath(databaseName),
                    driver = AndroidSQLiteDriver(),
                    databaseClass = StudentMistakeRoomDatabase::class,
                )
            helper.createDatabase(10).close()

            helper.runMigrationsAndValidate(
                version = 11,
                migrations = listOf(STUDENT_MISTAKE_MIGRATION_10_11),
            ).use { connection ->
                assertEquals(
                    0L,
                    connection.v11Long(
                        "SELECT COUNT(*) FROM student_problem_identity_receipt",
                    ),
                )
                assertEquals(
                    V11_IDENTITY_RECEIPT_COLUMNS,
                    connection.v11ColumnNames("student_problem_identity_receipt"),
                )
                assertEquals(
                    V11_IDENTITY_RECEIPT_INDEXES,
                    connection.v11IndexNames("student_problem_identity_receipt"),
                )
                assertEquals(
                    emptySet<String>(),
                    V11_IDENTITY_RECEIPT_TRIGGERS -
                        connection.v11TextSet(
                            """
                            SELECT name FROM sqlite_master
                            WHERE type = 'trigger'
                            """.trimIndent(),
                        ),
                )

                connection.execSQL(v11ReceiptInsertSql())
                assertEquals(
                    1L,
                    connection.v11Long(
                        "SELECT COUNT(*) FROM student_problem_identity_receipt",
                    ),
                )
                connection.assertV11Rejected(
                    """
                    UPDATE student_problem_identity_receipt
                    SET issuer_version = 'v2'
                    WHERE receipt_id = 'receipt-v11'
                    """.trimIndent(),
                )
                connection.assertV11Rejected(
                    """
                    DELETE FROM student_problem_identity_receipt
                    WHERE receipt_id = 'receipt-v11'
                    """.trimIndent(),
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
        Unit
    }

    @Test
    fun migration11To12AddsRenewalAndUsesTheIdentityReuseIndex() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val databaseName = "student-mistake-v11-v12-receipt.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        try {
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = context.getDatabasePath(databaseName),
                    driver = AndroidSQLiteDriver(),
                    databaseClass = StudentMistakeRoomDatabase::class,
                )
            helper.createDatabase(11).close()
            helper.runMigrationsAndValidate(
                version = 12,
                migrations = listOf(STUDENT_MISTAKE_MIGRATION_11_12),
            ).use { connection ->
                assertTrue(
                    "renewal_generation" in
                        connection.v11ColumnNames("student_problem_identity_receipt"),
                )
                assertTrue(
                    "fingerprint_version" in
                        connection.v11ColumnNames("student_problem_identity_receipt"),
                )
                assertTrue(
                    STUDENT_PROBLEM_IDENTITY_REUSE_INDEX_NAME in
                        connection.v11IndexNames("student_problem_identity_receipt"),
                )
            }
            val sqlite =
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                )
            try {
                val plan =
                    sqlite.rawQuery(
                        """
                        EXPLAIN QUERY PLAN
                        SELECT receipt_id
                        FROM student_problem_identity_receipt
                        WHERE learner_id = 'learner'
                          AND subject = 'MATH'
                          AND receipt_kind = 'EXACT_ASSET_SELECTION'
                          AND asset_manifest_canonical_fingerprint = '${"a".repeat(64)}'
                          AND selected_region_canonical_fingerprint = '${"b".repeat(64)}'
                          AND candidate_canonical_fingerprint = '${"c".repeat(64)}'
                        ORDER BY renewal_generation DESC,
                                 issued_at_epoch_millis DESC,
                                 receipt_id DESC
                        LIMIT 1
                        """.trimIndent(),
                        null,
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) add(cursor.getString(3))
                        }
                    }
                assertTrue(plan.any { STUDENT_PROBLEM_IDENTITY_REUSE_INDEX_NAME in it })
                assertTrue(plan.none { "TEMP B-TREE" in it.uppercase() })
            } finally {
                sqlite.close()
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
        Unit
    }

    @Test
    fun migration11To12RejectsSameNameNoOpImmutabilityTrigger() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val databaseName = "student-mistake-v11-v12-noop.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        try {
            val helper =
                MigrationTestHelper(
                    instrumentation = instrumentation,
                    file = context.getDatabasePath(databaseName),
                    driver = AndroidSQLiteDriver(),
                    databaseClass = StudentMistakeRoomDatabase::class,
                )
            helper.createDatabase(11).use { connection ->
                connection.execSQL(
                    "DROP TRIGGER IF EXISTS $STUDENT_PROBLEM_IDENTITY_RECEIPT_UPDATE_TRIGGER_NAME",
                )
                connection.execSQL(
                    """
                    CREATE TRIGGER $STUDENT_PROBLEM_IDENTITY_RECEIPT_UPDATE_TRIGGER_NAME
                    AFTER UPDATE ON student_problem_identity_receipt
                    BEGIN
                        SELECT 1;
                    END
                    """.trimIndent(),
                )
            }
            assertTrue(
                runCatching {
                    helper.runMigrationsAndValidate(
                        version = 12,
                        migrations = listOf(STUDENT_MISTAKE_MIGRATION_11_12),
                    ).close()
                }.isFailure,
            )
        } finally {
            context.deleteDatabase(databaseName)
        }
        Unit
    }

    @Test
    fun migration11To12ReadsAndRenewsAValidV11ExactReceiptWithoutReplayingGenerationOne() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val databaseName =
                "student-mistake-v11-v12-valid-exact.student-mistake-test.db"
            context.deleteDatabase(databaseName)
            try {
                val command = v11ExactCaptureCommand()
                val candidate = command.toIdentityCandidateBinding()
                val receiptId = "receipt-v11-exact"
                val issuerKeyId = "v11-exact-owner"
                val issuerVersion = "v1"
                val v11Fingerprint =
                    frozenV11ExactReceiptFingerprint(
                        receiptId = receiptId,
                        issuerKeyId = issuerKeyId,
                        issuerVersion = issuerVersion,
                        candidate = candidate,
                        issuedAtEpochMillis = 1_000L,
                        expiresAtEpochMillis = 2_000L,
                    )
                val helper =
                    MigrationTestHelper(
                        instrumentation = instrumentation,
                        file = context.getDatabasePath(databaseName),
                        driver = AndroidSQLiteDriver(),
                        databaseClass = StudentMistakeRoomDatabase::class,
                    )
                helper.createDatabase(11).use { connection ->
                    // Room schema exports do not contain callback-created triggers. A production
                    // v11 database always has this complete guard set, so restore that valid
                    // pre-migration state instead of weakening the v12 on-open verifier.
                    connection.installValidV11ImmutabilityGuards()
                    connection.execSQL(
                        v11ExactReceiptInsertSql(
                            receiptId = receiptId,
                            fingerprint = v11Fingerprint,
                            issuerKeyId = issuerKeyId,
                            issuerVersion = issuerVersion,
                            candidate = candidate,
                        ),
                    )
                }
                helper.runMigrationsAndValidate(
                    version = 12,
                    migrations = listOf(STUDENT_MISTAKE_MIGRATION_11_12),
                ).close()

                val database =
                    StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName)
                try {
                    val ledger = database.identityReceiptLedger()
                    val authority =
                        StudentProblemIdentityEvidenceAuthority.create(
                            issuerKeyId,
                            issuerVersion,
                        )
                    var now = 1_500L
                    val owner =
                        createStudentProblemIdentityEvidenceOwner(
                            learnerId = candidate.learnerId,
                            ledger = ledger,
                            authority = authority,
                            captureOccurrences =
                                RejectingV11OccurrencePort(candidate.learnerId),
                            nowEpochMillis = { now },
                            receiptValidityMillis = 1_000L,
                        )

                    val firstAttempt =
                        runCatching {
                            owner.saveExactAssetSelectionOrUnresolved(command)
                        }
                    assertEquals(
                        OCCURRENCE_SENTINEL,
                        firstAttempt.exceptionOrNull()?.message,
                    )
                    val migrated =
                        checkNotNull(
                            ledger.readLatestCandidate(
                                learnerId = candidate.learnerId,
                                subject = candidate.subject,
                                kind =
                                    StudentProblemIdentityReceiptKind
                                        .EXACT_ASSET_SELECTION,
                                assetManifestCanonicalFingerprint =
                                    candidate.assetManifestCanonicalFingerprint,
                                selectedRegionCanonicalFingerprint =
                                    candidate.selectedRegionCanonicalFingerprint,
                                candidateCanonicalFingerprint =
                                    candidate.canonicalFingerprint,
                            ),
                        )
                    assertEquals(
                        STUDENT_PROBLEM_IDENTITY_RECEIPT_FINGERPRINT_VERSION_V1,
                        migrated.fingerprintVersion,
                    )
                    assertEquals(1, migrated.renewalGeneration)
                    assertEquals(v11Fingerprint, migrated.receiptCanonicalFingerprint)

                    now = 2_001L
                    val renewalAttempt =
                        runCatching {
                            owner.saveExactAssetSelectionOrUnresolved(command)
                        }
                    assertEquals(
                        OCCURRENCE_SENTINEL,
                        renewalAttempt.exceptionOrNull()?.message,
                    )
                    val renewed =
                        checkNotNull(
                            ledger.readLatestCandidate(
                                learnerId = candidate.learnerId,
                                subject = candidate.subject,
                                kind =
                                    StudentProblemIdentityReceiptKind
                                        .EXACT_ASSET_SELECTION,
                                assetManifestCanonicalFingerprint =
                                    candidate.assetManifestCanonicalFingerprint,
                                selectedRegionCanonicalFingerprint =
                                    candidate.selectedRegionCanonicalFingerprint,
                                candidateCanonicalFingerprint =
                                    candidate.canonicalFingerprint,
                            ),
                        )
                    assertEquals(
                        STUDENT_PROBLEM_IDENTITY_RECEIPT_FINGERPRINT_VERSION_CURRENT,
                        renewed.fingerprintVersion,
                    )
                    assertEquals(2, renewed.renewalGeneration)
                    assertNotEquals(migrated.receiptId, renewed.receiptId)
                    assertNotEquals(
                        migrated.receiptCanonicalFingerprint,
                        renewed.receiptCanonicalFingerprint,
                    )
                    assertEquals(migrated, ledger.read(migrated.receiptId))
                } finally {
                    database.close()
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
            Unit
        }
}

private class RejectingV11OccurrencePort(
    override val learnerId: String,
) : LearnerBoundStudentCaptureOccurrencePort {
    override suspend fun save(
        command: SaveStudentCaptureOccurrenceCommand,
    ): StudentCaptureOccurrenceReceipt = error(OCCURRENCE_SENTINEL)
}

private fun SQLiteConnection.installValidV11ImmutabilityGuards() {
    createStudentCutoverAndMigrationLedgerImmutabilityTriggers(this)
    createStudentImportSnapshotImmutabilityTriggers(this)
    createStudentProblemOrganizationImmutabilityTriggers(this)
    createStudentProblemIdentityReceiptImmutabilityTriggers(this)
}

private fun v11ExactCaptureCommand(): SaveStudentCaptureOccurrenceCommand {
    val intentId = "v11-exact"
    val imageId = "image-$intentId"
    val document =
        CapturedQuestionDocument(
            document =
                QuestionDocument(
                    id = "document-$intentId",
                    title = "迁移题",
                    blocks =
                        listOf(
                            ContentBlock.Paragraph(
                                id = "stem",
                                markdown = "求函数值。",
                            ),
                        ),
                ),
            blockEvidence =
                listOf(
                    QuestionBlockEvidence(
                        blockId = "stem",
                        sourceAssetId = imageId,
                        sourceRegion = NormalizedSourceRegion(0.1, 0.1, 0.9, 0.9),
                        writingLayer = WritingLayer.PRINTED,
                        provenance = QuestionBlockProvenance.USER_CORRECTION,
                        confidence = 1.0,
                        reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                    ),
                ),
        )
    val problem =
        StudentProblemRef(
            learnerId = "learner-v11-exact",
            subject = SubjectKind.MATH,
            problemId = "problem-$intentId",
            practiceUnitId = "practice-$intentId",
        )
    val revision =
        StudentProblemRevisionRef(
            problem = problem,
            revisionId = "revision-$intentId",
            revisionNumber = 1,
            documentCanonicalFingerprint =
                CapturedQuestionDocumentFingerprint.of(document),
        )
    val source =
        TutorStudentCaptureSaveSource(
            intentId = intentId,
            sourceCanonicalFingerprint = v11Fingerprint("source:$intentId"),
            saveRequestId = "save-$intentId",
            sessionId = "session-$intentId",
            draftId = "draft-$intentId",
            draftRevisionNumber = 1,
            occurredAtEpochMillis = 1_000L,
        )
    val target =
        SaveTargetConfirmedStudentMistakeCommand(
            problem =
                CommitStudentProblemCommand(
                    revision = revision,
                    title = document.document.title,
                    stemMarkdown =
                        QuestionDocumentMarkdownProjection.project(document.document),
                    practiceUnitKind = StudentPracticeUnitKind.WHOLE_PROBLEM,
                    practiceUnitTitle = "整题",
                    itemFamilyId = "family-$intentId",
                    estimatedDurationSeconds = 120,
                    sourceBundleId = null,
                    partIds = emptyList(),
                    originalImages =
                        listOf(
                            StudentProblemImageReference(
                                imageReferenceId = imageId,
                                localContentUri = "content://capture/$intentId",
                                contentCanonicalFingerprint =
                                    v11Fingerprint("asset:$intentId"),
                                mediaType = "image/jpeg",
                                ordinal = 0,
                                widthPixels = 1_200,
                                heightPixels = 900,
                                byteSize = 42_000L,
                                selectedRegions =
                                    listOf(
                                        NormalizedSourceRegion(0.1, 0.1, 0.9, 0.9),
                                    ),
                            ),
                        ),
                    committedAtEpochMillis = source.occurredAtEpochMillis,
                    errorBookEntryId = "entry-$intentId",
                    capturedQuestionDocument = document,
                ),
            confirmedAtEpochMillis = source.occurredAtEpochMillis,
        )
    return SaveStudentCaptureOccurrenceCommand(
        capture = SaveStudentOwnedCaptureCommand(source = source, target = target),
        occurrence =
            AppendStudentProblemErrorOccurrenceCommand(
                occurrenceId = "occurrence-$intentId",
                idempotencyKey = intentId,
                problemRevision = revision,
                batchCanonicalFingerprint = v11Fingerprint("batch:$intentId"),
                importSourceCanonicalFingerprint = v11Fingerprint("import:$intentId"),
                occurredAtEpochMillis = source.occurredAtEpochMillis,
                importedAtEpochMillis = source.occurredAtEpochMillis,
                attributionStatus =
                    ProblemErrorAttributionResolutionStatus.UNRESOLVED,
            ),
    )
}

private fun v11ExactReceiptInsertSql(
    receiptId: String,
    fingerprint: String,
    issuerKeyId: String,
    issuerVersion: String,
    candidate: StudentProblemIdentityCandidateBinding,
): String =
    """
    INSERT INTO student_problem_identity_receipt (
        receipt_id, receipt_kind, receipt_canonical_fingerprint,
        issuer_key_id, issuer_version, learner_id, subject,
        source_kind, source_intent_id, idempotency_key,
        source_canonical_fingerprint, locator_namespace, locator_version,
        item_locator_canonical_fingerprint, problem_id, practice_unit_id,
        revision_id, revision_number, document_canonical_fingerprint,
        target_canonical_fingerprint, asset_manifest_canonical_fingerprint,
        selected_region_canonical_fingerprint, candidate_canonical_fingerprint,
        review_authority_kind, existing_identity_namespace,
        existing_identity_version, existing_identity_stable_key,
        existing_identity_canonical_fingerprint, review_case_id,
        review_revision, review_decision_canonical_fingerprint,
        issued_at_epoch_millis, expires_at_epoch_millis
    ) VALUES (
        '$receiptId', 'EXACT_ASSET_SELECTION', '$fingerprint',
        '$issuerKeyId', '$issuerVersion', '${candidate.learnerId}', '${candidate.subject}',
        '${candidate.sourceKind}', '${candidate.sourceIntentId}', '${candidate.idempotencyKey}',
        '${candidate.sourceCanonicalFingerprint}', NULL, NULL, NULL,
        '${candidate.problemId}', '${candidate.practiceUnitId}',
        '${candidate.revisionId}', ${candidate.revisionNumber},
        '${candidate.documentCanonicalFingerprint}',
        '${candidate.targetCanonicalFingerprint}',
        '${candidate.assetManifestCanonicalFingerprint}',
        '${candidate.selectedRegionCanonicalFingerprint}',
        '${candidate.canonicalFingerprint}',
        NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        1000, 2000
    )
    """.trimIndent()

private fun v11Fingerprint(value: String): String =
    CanonicalSha256("student-mistake-v11-test")
        .field("value", value)
        .finish()

/**
 * Frozen copy of the v11 fingerprint wire protocol. It intentionally does not call the v12
 * dispatcher, so this migration fixture detects an accidental rewrite of legacy verification.
 */
private fun frozenV11ExactReceiptFingerprint(
    receiptId: String,
    issuerKeyId: String,
    issuerVersion: String,
    candidate: StudentProblemIdentityCandidateBinding,
    issuedAtEpochMillis: Long,
    expiresAtEpochMillis: Long,
): String =
    CanonicalSha256("student-problem-identity-receipt-v1")
        .field("receiptId", receiptId)
        .field(
            "receiptKind",
            StudentProblemIdentityReceiptKind.EXACT_ASSET_SELECTION.name,
        )
        .field("issuerKeyId", issuerKeyId)
        .field("issuerVersion", issuerVersion)
        .field("candidateCanonicalFingerprint", candidate.canonicalFingerprint)
        .nullableField("locatorNamespace", null)
        .nullableField("locatorVersion", null)
        .nullableField("itemLocatorCanonicalFingerprint", null)
        .nullableField("reviewAuthorityKind", null)
        .nullableField("existingIdentityNamespace", null)
        .nullableField("existingIdentityVersion", null)
        .nullableField("existingIdentityStableKey", null)
        .nullableField("existingIdentityCanonicalFingerprint", null)
        .nullableField("reviewCaseId", null)
        .nullableLongField("reviewRevision", null)
        .nullableField("reviewDecisionCanonicalFingerprint", null)
        .field("issuedAtEpochMillis", issuedAtEpochMillis)
        .field("expiresAtEpochMillis", expiresAtEpochMillis)
        .finish()

private const val OCCURRENCE_SENTINEL = "occurrence-not-part-of-receipt-migration-test"

private fun v11ReceiptInsertSql(): String =
    """
    INSERT INTO student_problem_identity_receipt (
        receipt_id, receipt_kind, receipt_canonical_fingerprint,
        issuer_key_id, issuer_version, learner_id, subject,
        source_kind, source_intent_id, idempotency_key,
        source_canonical_fingerprint, locator_namespace, locator_version,
        item_locator_canonical_fingerprint, problem_id, practice_unit_id,
        revision_id, revision_number, document_canonical_fingerprint,
        target_canonical_fingerprint, asset_manifest_canonical_fingerprint,
        selected_region_canonical_fingerprint, candidate_canonical_fingerprint,
        review_authority_kind, existing_identity_namespace,
        existing_identity_version, existing_identity_stable_key,
        existing_identity_canonical_fingerprint, review_case_id,
        review_revision, review_decision_canonical_fingerprint,
        issued_at_epoch_millis, expires_at_epoch_millis
    ) VALUES (
        'receipt-v11', 'TRUSTED_SOURCE', '${"1".repeat(64)}',
        'issuer-v11', 'v1', 'learner-v11', 'MATH',
        'TUTOR_SESSION', 'intent-v11', 'intent-v11',
        '${"2".repeat(64)}', 'publisher-item', 'v1',
        '${"3".repeat(64)}', 'problem-v11', 'practice-v11',
        'revision-v11', 1, '${"4".repeat(64)}',
        '${"5".repeat(64)}', '${"6".repeat(64)}',
        '${"7".repeat(64)}', '${"8".repeat(64)}',
        NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
        1000, 2000
    )
    """.trimIndent()

private fun SQLiteConnection.v11ColumnNames(tableName: String): Set<String> =
    prepare("PRAGMA table_info(`$tableName`)").use { statement ->
        buildSet {
            while (statement.step()) add(statement.getText(1))
        }
    }

private fun SQLiteConnection.v11IndexNames(tableName: String): Set<String> =
    prepare("PRAGMA index_list(`$tableName`)").use { statement ->
        buildSet {
            while (statement.step()) {
                val name = statement.getText(1)
                if (!name.startsWith("sqlite_autoindex_")) add(name)
            }
        }
    }

private fun SQLiteConnection.v11Long(sql: String): Long =
    prepare(sql).use { statement ->
        check(statement.step())
        statement.getLong(0)
    }

private fun SQLiteConnection.v11TextSet(sql: String): Set<String> =
    prepare(sql).use { statement ->
        buildSet {
            while (statement.step()) add(statement.getText(0))
        }
    }

private fun SQLiteConnection.assertV11Rejected(sql: String) {
    assertTrue(runCatching { execSQL(sql) }.isFailure)
}

private val V11_IDENTITY_RECEIPT_COLUMNS =
    setOf(
        "receipt_id",
        "receipt_kind",
        "receipt_canonical_fingerprint",
        "issuer_key_id",
        "issuer_version",
        "learner_id",
        "subject",
        "source_kind",
        "source_intent_id",
        "idempotency_key",
        "source_canonical_fingerprint",
        "locator_namespace",
        "locator_version",
        "item_locator_canonical_fingerprint",
        "problem_id",
        "practice_unit_id",
        "revision_id",
        "revision_number",
        "document_canonical_fingerprint",
        "target_canonical_fingerprint",
        "asset_manifest_canonical_fingerprint",
        "selected_region_canonical_fingerprint",
        "candidate_canonical_fingerprint",
        "review_authority_kind",
        "existing_identity_namespace",
        "existing_identity_version",
        "existing_identity_stable_key",
        "existing_identity_canonical_fingerprint",
        "review_case_id",
        "review_revision",
        "review_decision_canonical_fingerprint",
        "issued_at_epoch_millis",
        "expires_at_epoch_millis",
    )

private val V11_IDENTITY_RECEIPT_INDEXES =
    setOf(
        "index_student_problem_identity_receipt_receipt_canonical_fingerprint",
        "index_student_problem_identity_receipt_" +
            "learner_id_receipt_kind_expires_at_epoch_millis",
        "index_student_problem_identity_receipt_" +
            "learner_id_source_intent_id_idempotency_key",
    )

private val V11_IDENTITY_RECEIPT_TRIGGERS =
    setOf(
        "immutable_student_problem_identity_receipt_update",
        "immutable_student_problem_identity_receipt_delete",
    )
