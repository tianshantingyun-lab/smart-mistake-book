package com.tingyun.smartmistakebook.core.student.mistake.database

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearnerBoundStudentMistakeLibraryPortInstrumentedTest {
    private val knowledgeProofAuthority = KnowledgeReferenceProofAuthority.create()

    @Test
    fun unicodeSearchNormalizesCompatibilityWidthAndKeepsCurrentRevisionInSync() = runBlocking {
        withStore("library-unicode-search") { store ->
            val first =
                problemCommand(
                    learnerId = LEARNER_A,
                    suffix = "unicode",
                    committedAtEpochMillis = 100,
                    title = "ＦＵＮＣＴＩＯＮ ÉQUATION",
                    stemMarkdown = "研究ＦＵＮＣＴＩＯＮ与ÉQUATION。",
                )
            val entryId = store.save(first)
            val library = store.libraryForLearner(LEARNER_A)

            val asciiResult =
                library.readPage(
                    StudentMistakeLibraryPageRequest(
                        filter = StudentMistakeLibraryFilter(text = "function"),
                    ),
                ) as StudentMistakeLibraryPageResult.Content
            assertEquals(listOf(entryId), asciiResult.items.map { it.entryId.value })
            val accentedResult =
                library.readPage(
                    StudentMistakeLibraryPageRequest(
                        filter = StudentMistakeLibraryFilter(text = "équation"),
                    ),
                ) as StudentMistakeLibraryPageResult.Content
            assertEquals(listOf(entryId), accentedResult.items.map { it.entryId.value })

            store.commitProblem(
                problemCommand(
                    learnerId = LEARNER_A,
                    suffix = "unicode",
                    revisionNumber = 2,
                    committedAtEpochMillis = 200,
                    title = "二次函数",
                    stemMarkdown = "研究二次函数的单调性。",
                ),
            )
            val oldTextResult =
                library.readPage(
                    StudentMistakeLibraryPageRequest(
                        filter = StudentMistakeLibraryFilter(text = "function"),
                    ),
                ) as StudentMistakeLibraryPageResult.Content
            assertTrue(oldTextResult.items.isEmpty())
            val currentTextResult =
                library.readPage(
                    StudentMistakeLibraryPageRequest(
                        filter = StudentMistakeLibraryFilter(text = "二次函数"),
                    ),
                ) as StudentMistakeLibraryPageResult.Content
            assertEquals(listOf(entryId), currentTextResult.items.map { it.entryId.value })
        }
    }

    @Test
    fun searchBackfillResumesAcrossReopenAndUsesFtsBeforeDocumentLookup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = testDatabaseName("search-resume")
        context.deleteDatabase(databaseName)
        var store = RoomStudentMistakeStore(
            database =
                StudentMistakeStoreFactory.openDatabaseForTest(
                    context,
                    databaseName,
                ),
            knowledgeReferenceVerifier = knowledgeProofAuthority.verifier,
        )
        try {
            repeat(70) { index ->
                store.save(
                    problemCommand(
                        learnerId = LEARNER_A,
                        suffix = "batch-${index.toString().padStart(3, '0')}",
                        committedAtEpochMillis = 100L + index,
                    ),
                )
            }
            val preparing =
                store.libraryForLearner(LEARNER_A)
                    .readPage(
                        StudentMistakeLibraryPageRequest(
                            filter = StudentMistakeLibraryFilter(text = "函数"),
                        ),
                    )
            assertEquals(
                StudentMistakeLibraryPageResult.Preparing(indexedDocumentCount = 64),
                preparing,
            )
            store.close()

            store =
                RoomStudentMistakeStore(
                    database =
                        StudentMistakeStoreFactory.openDatabaseForTest(
                            context,
                            databaseName,
                        ),
                    knowledgeReferenceVerifier = knowledgeProofAuthority.verifier,
                )
            val library = store.libraryForLearner(LEARNER_A)
            assertEquals(
                StudentMistakeSearchIndexStatus.Ready,
                library.prepareSearchIndex(maxDocuments = 10),
            )
            val content =
                library.readPage(
                    StudentMistakeLibraryPageRequest(
                        filter = StudentMistakeLibraryFilter(text = "函数"),
                        limit = MAX_LIBRARY_PAGE_SIZE,
                    ),
                ) as StudentMistakeLibraryPageResult.Content
            assertEquals(64, content.items.size)
            assertTrue(content.nextCursor != null)
            store.close()

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
                        SELECT search_document.revision_id
                        FROM student_problem_search_fts AS search_fts
                        INNER JOIN student_problem_search_document AS search_document
                          ON search_document.rowid = search_fts.docid
                        WHERE search_fts.tokenized_text MATCH '函 数'
                          AND instr(search_document.normalized_text, '函数') > 0
                        """.trimIndent(),
                        null,
                    ).use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) add(cursor.getString(3))
                        }
                    }
                assertTrue(
                    plan.any {
                        it.contains("VIRTUAL TABLE INDEX", ignoreCase = true)
                    },
                )
                assertFalse(
                    plan.any {
                        it.contains("SCAN search_document", ignoreCase = true)
                    },
                )
            } finally {
                sqlite.close()
            }
        } finally {
            runCatching { store.close() }
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun learnerBoundPaginationRejectsCrossLearnerFilterDriftAndSnapshotDrift() = runBlocking {
        withStore("library-pagination") { store ->
            listOf("a", "b", "c").forEach { suffix ->
                store.save(
                    problemCommand(
                        learnerId = LEARNER_A,
                        suffix = suffix,
                        committedAtEpochMillis = 1_000,
                    ),
                )
            }
            val otherEntry =
                store.save(
                    problemCommand(
                        learnerId = LEARNER_B,
                        suffix = "a",
                        committedAtEpochMillis = 1_000,
                    ),
                )
            listOf("b", "c").forEach { suffix ->
                store.save(
                    problemCommand(
                        learnerId = LEARNER_B,
                        suffix = suffix,
                        committedAtEpochMillis = 1_000,
                    ),
                )
            }
            val library = store.libraryForLearner(LEARNER_A)

            assertTrue(library.observeChangeVersion().first() > 0)
            assertNull(library.readDetail(StudentMistakeEntryId(otherEntry)))

            val first =
                library.readPage(
                    StudentMistakeLibraryPageRequest(limit = 2),
                ) as StudentMistakeLibraryPageResult.Content
            assertEquals(
                listOf("entry-learner-a-a", "entry-learner-a-b"),
                first.items.map { it.entryId.value },
            )
            val firstCursor = checkNotNull(first.nextCursor)
            val otherLibrary = store.libraryForLearner(LEARNER_B)
            assertEquals(
                library.observeChangeVersion().first(),
                otherLibrary.observeChangeVersion().first(),
            )
            assertEquals(
                StudentMistakeLibraryPageResult.ReloadRequired,
                otherLibrary.readPage(
                    StudentMistakeLibraryPageRequest(
                        cursor = firstCursor,
                        limit = 2,
                    ),
                ),
            )
            val second =
                library.readPage(
                    StudentMistakeLibraryPageRequest(
                        cursor = firstCursor,
                        limit = 2,
                    ),
                ) as StudentMistakeLibraryPageResult.Content
            assertEquals(
                listOf("entry-learner-a-c"),
                second.items.map { it.entryId.value },
            )

            val filteredFirst =
                library.readPage(
                    StudentMistakeLibraryPageRequest(
                        filter = StudentMistakeLibraryFilter(subject = SubjectKind.MATH),
                        limit = 1,
                    ),
                ) as StudentMistakeLibraryPageResult.Content
            assertEquals(
                StudentMistakeLibraryPageResult.ReloadRequired,
                library.readPage(
                    StudentMistakeLibraryPageRequest(
                        filter =
                            StudentMistakeLibraryFilter(
                                subject = SubjectKind.MATH,
                                favoriteOnly = true,
                            ),
                        cursor = checkNotNull(filteredFirst.nextCursor),
                        limit = 1,
                    ),
                ),
            )
            val normalizedSearch =
                library.readPage(
                    StudentMistakeLibraryPageRequest(
                        filter = StudentMistakeLibraryFilter(text = "函數"),
                    ),
                ) as StudentMistakeLibraryPageResult.Content
            assertTrue(normalizedSearch.items.isEmpty())
            val chineseSearch =
                library.readPage(
                    StudentMistakeLibraryPageRequest(
                        filter = StudentMistakeLibraryFilter(text = "函数"),
                    ),
                ) as StudentMistakeLibraryPageResult.Content
            assertEquals(3, chineseSearch.items.size)

            store.save(
                problemCommand(
                    learnerId = LEARNER_A,
                    suffix = "d",
                    committedAtEpochMillis = 1_000,
                ),
            )
            assertEquals(
                StudentMistakeLibraryPageResult.ReloadRequired,
                library.readPage(
                    StudentMistakeLibraryPageRequest(
                        cursor = firstCursor,
                        limit = 2,
                    ),
                ),
            )
        }
    }

    @Test
    fun listDetailAndFacetsUseOnlyAcceptedClassificationsFromCurrentRevisions() = runBlocking {
        withStore("library-classification") { store ->
            val firstRevision =
                problemCommand(
                    learnerId = LEARNER_A,
                    suffix = "first",
                    revisionNumber = 1,
                    committedAtEpochMillis = 100,
                )
            val firstEntry = store.save(firstRevision)
            store.recordClassifications(
                classificationCommand(
                    firstRevision.revision,
                    sectionKey = "math.old-section",
                    sectionName = "旧板块",
                    knowledgeNodeId = "math.old-node",
                    idPrefix = "old-first",
                ),
            )
            val currentFirst =
                problemCommand(
                    learnerId = LEARNER_A,
                    suffix = "first",
                    revisionNumber = 2,
                    committedAtEpochMillis = 200,
                )
            store.commitProblem(currentFirst)
            store.recordClassifications(
                classificationCommand(
                    currentFirst.revision,
                    sectionKey = SECTION_KEY,
                    sectionName = "函数",
                    knowledgeNodeId = KNOWLEDGE_NODE_ID,
                    idPrefix = "current-first",
                ),
            )

            val secondRevision =
                problemCommand(
                    learnerId = LEARNER_A,
                    suffix = "second",
                    revisionNumber = 1,
                    committedAtEpochMillis = 300,
                )
            store.save(secondRevision)
            store.recordClassifications(
                classificationCommand(
                    secondRevision.revision,
                    sectionKey = SECTION_KEY,
                    sectionName = "函数（旧模型文案漂移）",
                    knowledgeNodeId = KNOWLEDGE_NODE_ID,
                    idPrefix = "current-second",
                ),
            )

            val library = store.libraryForLearner(LEARNER_A)
            val page =
                library.readPage(
                    StudentMistakeLibraryPageRequest(limit = 10),
                ) as StudentMistakeLibraryPageResult.Content
            val first = page.items.single { it.entryId.value == firstEntry }
            assertEquals(currentFirst.revision, first.problemRevision)
            assertEquals(listOf(SECTION_KEY), first.sections.map { it.key.value })
            assertEquals(
                listOf(KNOWLEDGE_NODE_ID),
                first.knowledgeNodes.map { it.knowledgeNodeId },
            )
            val detail = checkNotNull(library.readDetail(StudentMistakeEntryId(firstEntry)))
            assertEquals(
                "content://student-mistakes/learner-a/first/2",
                detail.images.single().localContentUri,
            )
            assertEquals(listOf(SECTION_KEY), detail.sections.map { it.key.value })
            assertEquals(
                listOf(KNOWLEDGE_NODE_ID),
                detail.knowledgeNodes.map { it.knowledgeNodeId },
            )

            val facets = library.readFacets()
            assertEquals(
                2L,
                facets.subjects.single { it.subject == SubjectKind.MATH }.problemCount,
            )
            assertEquals(
                2L,
                facets.sections.single { it.section.key.value == SECTION_KEY }.problemCount,
            )
            assertEquals(
                2L,
                facets.knowledgeNodes
                    .single { it.knowledgeNode.knowledgeNodeId == KNOWLEDGE_NODE_ID }
                    .problemCount,
            )
            assertTrue(
                facets.sections.none { it.section.key.value == "math.old-section" },
            )
            assertTrue(
                facets.knowledgeNodes.none {
                    it.knowledgeNode.knowledgeNodeId == "math.old-node"
                },
            )
        }
    }

    @Test
    fun migrationThreeToSixPreservesEveryLegacyTableRowAndAddsReadIndexes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = testDatabaseName("migration")
        context.deleteDatabase(databaseName)
        createDatabaseFromExportedSchema(context, databaseName, version = 3)
        val path = context.getDatabasePath(databaseName)
        val beforeDatabase = SQLiteDatabase.openDatabase(path.absolutePath, null, 0)
        val beforeCounts =
            try {
                beforeDatabase.execSQL(
                    """
                    INSERT INTO student_store_metadata(
                        metadata_key, metadata_value,
                        created_at_epoch_millis, updated_at_epoch_millis
                    ) VALUES ('migration-marker', 'kept', 10, 20)
                    """.trimIndent(),
                )
                beforeDatabase.execSQL(
                    """
                    INSERT INTO student_learner_change(learner_id, change_version)
                    VALUES ('learner-migration', 7)
                    """.trimIndent(),
                )
                beforeDatabase.tableRowCounts()
            } finally {
                beforeDatabase.close()
            }

        val room = StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName)
        try {
            runBlocking {
                room.libraryDao().readLibraryFacetSnapshot("learner-migration")
            }
        } finally {
            room.close()
        }

        val migrated = SQLiteDatabase.openDatabase(path.absolutePath, null, 0)
        try {
            val afterCounts = migrated.tableRowCounts()
            assertEquals(
                beforeCounts - "student_store_metadata",
                afterCounts.filterKeys(beforeCounts::containsKey) - "student_store_metadata",
            )
            assertEquals(2L, afterCounts["student_store_metadata"])
            assertEquals(EXPECTED_V4_INDEXES, migrated.libraryIndexNames())
            val normalPlan = migrated.libraryPageQueryPlan(favoriteOnly = false)
            assertTrue(
                normalPlan.any {
                    it.contains(
                        "index_student_problem_collection_learner_id_" +
                            "mistake_state_changed_at_epoch_millis_problem_id",
                    )
                },
            )
            assertFalse(normalPlan.any { it.contains("TEMP B-TREE", ignoreCase = true) })
            val favoritePlan = migrated.libraryPageQueryPlan(favoriteOnly = true)
            assertTrue(
                favoritePlan.any {
                    it.contains(
                        "index_student_problem_collection_learner_id_" +
                            "mistake_state_favorite_changed_at_epoch_millis_problem_id",
                    )
                },
            )
            assertFalse(favoritePlan.any { it.contains("TEMP B-TREE", ignoreCase = true) })
            assertEquals(
                "kept",
                migrated.rawQuery(
                    """
                    SELECT metadata_value
                    FROM student_store_metadata
                    WHERE metadata_key = 'migration-marker'
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getString(0)
                },
            )
        } finally {
            migrated.close()
            context.deleteDatabase(databaseName)
        }
    }

    private suspend fun RoomStudentMistakeStore.save(
        command: CommitStudentProblemCommand,
        favorite: Boolean = false,
    ): String {
        val receipt =
            saveConfirmedMistake(
                SaveTargetConfirmedStudentMistakeCommand(
                    problem = command,
                    confirmedAtEpochMillis = command.committedAtEpochMillis,
                    favorite = favorite,
                ),
            )
        return receipt.errorBookEntryId
    }

    private fun problemCommand(
        learnerId: String,
        suffix: String,
        revisionNumber: Int = 1,
        committedAtEpochMillis: Long,
        title: String = "函数题 $suffix",
        stemMarkdown: String = "研究函数 $suffix 的性质。",
    ): CommitStudentProblemCommand {
        val problemId = "problem-$learnerId-$suffix"
        val practiceUnitId = "unit-$learnerId-$suffix"
        val revisionId = "revision-$learnerId-$suffix-$revisionNumber"
        val revision =
            StudentProblemRevisionRef(
                problem =
                    StudentProblemRef(
                        learnerId = learnerId,
                        subject = SubjectKind.MATH,
                        problemId = problemId,
                        practiceUnitId = practiceUnitId,
                    ),
                revisionId = revisionId,
                revisionNumber = revisionNumber,
                documentCanonicalFingerprint =
                    if (revisionNumber == 1) "a".repeat(64) else "b".repeat(64),
            )
        return CommitStudentProblemCommand(
            revision = revision,
            title = title,
            stemMarkdown = stemMarkdown,
            practiceUnitKind = StudentPracticeUnitKind.WHOLE_PROBLEM,
            practiceUnitTitle = "函数题 $suffix",
            itemFamilyId = "family-$suffix",
            estimatedDurationSeconds = 180,
            sourceBundleId = null,
            partIds = emptyList(),
            originalImages =
                listOf(
                    StudentProblemImageReference(
                        imageReferenceId = "image-$learnerId-$suffix-$revisionNumber",
                        localContentUri =
                            "content://student-mistakes/$learnerId/$suffix/$revisionNumber",
                        contentCanonicalFingerprint = "c".repeat(64),
                        mediaType = "image/jpeg",
                        ordinal = 0,
                    ),
                ),
            committedAtEpochMillis = committedAtEpochMillis,
            errorBookEntryId = "entry-$learnerId-$suffix",
        )
    }

    private fun classificationCommand(
        revision: StudentProblemRevisionRef,
        sectionKey: String,
        sectionName: String,
        knowledgeNodeId: String,
        idPrefix: String,
    ): RecordStudentProblemClassificationsCommand {
        val knowledgeNode =
            KnowledgeNodeRef(
                subject = SubjectKind.MATH,
                knowledgeNodeId = knowledgeNodeId,
                taxonomyVersion = "taxonomy-v1",
                knowledgePackVersion = "pack-v1",
            )
        val section =
            StudentProblemClassificationResult(
                classificationId = "$idPrefix-section",
                problemRevision = revision,
                dimension = StudentProblemClassificationDimension.CURRICULUM_SECTION,
                labelId = sectionKey,
                displayName = sectionName,
                knowledgeNode = null,
                modelProviderId = "provider-test",
                modelId = "model-test",
                classifierVersion = "classifier-v1",
                resultCanonicalFingerprint = "d".repeat(64),
                status = StudentProblemClassificationStatus.ACCEPTED,
                recordedAtEpochMillis = 400,
            )
        val knowledge =
            StudentProblemClassificationResult(
                classificationId = "$idPrefix-knowledge",
                problemRevision = revision,
                dimension = StudentProblemClassificationDimension.KNOWLEDGE,
                labelId = knowledgeNodeId,
                displayName = null,
                knowledgeNode = knowledgeNode,
                modelProviderId = "provider-test",
                modelId = "model-test",
                classifierVersion = "classifier-v1",
                resultCanonicalFingerprint = "e".repeat(64),
                status = StudentProblemClassificationStatus.ACCEPTED,
                recordedAtEpochMillis = 400,
            )
        return RecordStudentProblemClassificationsCommand(
            problemRevision = revision,
            results = listOf(section, knowledge),
            verifiedKnowledgeReferences =
                mapOf(
                    knowledge.classificationId to
                        knowledgeProofAuthority.issuer.issue(
                            knowledgeNode,
                            "f".repeat(64),
                            1,
                        ),
                ),
        )
    }

    private suspend fun withStore(
        suffix: String,
        block: suspend (RoomStudentMistakeStore) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = testDatabaseName(suffix)
        context.deleteDatabase(databaseName)
        val database = StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName)
        val store =
            RoomStudentMistakeStore(
                database = database,
                knowledgeReferenceVerifier = knowledgeProofAuthority.verifier,
            )
        try {
            block(store)
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun createDatabaseFromExportedSchema(
        context: android.content.Context,
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

    private fun SQLiteDatabase.tableRowCounts(): Map<String, Long> {
        val tableNames =
            rawQuery(
                """
                SELECT name
                FROM sqlite_master
                WHERE type = 'table'
                  AND name LIKE 'student_%'
                ORDER BY name
                """.trimIndent(),
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
        return tableNames.associateWith { table ->
            rawQuery("SELECT COUNT(*) FROM `$table`", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getLong(0)
            }
        }
    }

    private fun SQLiteDatabase.libraryIndexNames(): Set<String> =
        rawQuery(
            """
            SELECT name
            FROM sqlite_master
            WHERE type = 'index'
              AND name IN (
                'index_student_problem_document_learner_id_error_book_entry_id',
                'index_student_problem_collection_learner_id_mistake_state_changed_at_epoch_millis',
                'index_student_problem_collection_learner_id_favorite_changed_at_epoch_millis',
                'index_student_problem_collection_learner_id_mistake_state_changed_at_epoch_millis_problem_id',
                'index_student_problem_collection_learner_id_mistake_state_favorite_changed_at_epoch_millis_problem_id',
                'index_student_problem_classification_result_basis_revision_id_dimension_status_knowledge_subject_knowledge_node_id_knowledge_taxonomy_version',
                'index_student_problem_classification_result_basis_revision_id_dimension_status_knowledge_subject_knowledge_node_id_knowledge_taxonomy_version_knowledge_pack_version'
              )
            ORDER BY name
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }

    private fun SQLiteDatabase.libraryPageQueryPlan(
        favoriteOnly: Boolean,
    ): List<String> {
        val favoritePredicate =
            if (favoriteOnly) {
                "AND collection.favorite = 1"
            } else {
                ""
            }
        return rawQuery(
            """
            EXPLAIN QUERY PLAN
            SELECT collection.problem_id
            FROM student_problem_collection AS collection
            INNER JOIN student_problem_document AS problem
              ON problem.problem_id = collection.problem_id
             AND problem.learner_id = collection.learner_id
            INNER JOIN student_problem_revision AS revision
              ON revision.revision_id = problem.current_revision_id
            INNER JOIN student_practice_unit AS unit
              ON unit.practice_unit_id = collection.practice_unit_id
             AND unit.problem_id = collection.problem_id
            WHERE collection.learner_id = 'learner-plan'
              AND collection.mistake_state = 'ACTIVE'
              AND problem.lifecycle_state = 'ACTIVE'
              AND problem.error_book_entry_id IS NOT NULL
              $favoritePredicate
              AND (
                collection.changed_at_epoch_millis < 1000 OR
                (
                  collection.changed_at_epoch_millis = 1000 AND
                  collection.problem_id > 'problem-cursor'
                )
              )
            ORDER BY collection.changed_at_epoch_millis DESC,
                     collection.problem_id ASC
            LIMIT 65
            """.trimIndent(),
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(3))
            }
        }
    }

    private fun testDatabaseName(suffix: String): String =
        "student-library-$suffix-${System.nanoTime()}" +
            StudentMistakeStoreFactory.TEST_DATABASE_SUFFIX

    private companion object {
        const val LEARNER_A = "learner-a"
        const val LEARNER_B = "learner-b"
        const val SECTION_KEY = "math.function"
        const val KNOWLEDGE_NODE_ID = "math.function.quadratic"
        val EXPECTED_V4_INDEXES =
            setOf(
                "index_student_problem_document_learner_id_error_book_entry_id",
                "index_student_problem_collection_learner_id_mistake_state_changed_at_epoch_millis_problem_id",
                "index_student_problem_collection_learner_id_mistake_state_favorite_changed_at_epoch_millis_problem_id",
                "index_student_problem_classification_result_basis_revision_id_dimension_status_knowledge_subject_knowledge_node_id_knowledge_taxonomy_version_knowledge_pack_version",
            )
    }
}
