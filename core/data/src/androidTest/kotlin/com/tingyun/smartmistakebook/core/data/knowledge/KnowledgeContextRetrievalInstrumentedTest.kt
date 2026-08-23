package com.tingyun.smartmistakebook.core.data.knowledge

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeRelationContract
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeRelationRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSourceBindingSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeSearchFeatureExtractor
import com.tingyun.smartmistakebook.core.database.KnowledgeSourceSeedRecord
import com.tingyun.smartmistakebook.core.database.ProjectionCommit
import com.tingyun.smartmistakebook.core.database.ProjectionCommitMode
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceLicenseStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceType
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KnowledgeContextRetrievalInstrumentedTest {
    @Test
    fun bundledSubjectsRecallExpectedKnowledgeWithoutCrossSubjectCandidates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "knowledge-recall-quality-${System.nanoTime()}.db"
        val store = StudyDatabaseFactory.open(context, databaseName)
        try {
            BundledKnowledgeBaseInstaller.install(store)
            val cases = listOf(
                RecallCase("CHINESE", "用一句话概括这段说明文字的主要信息。", "extract-summarize-main-information"),
                RecallCase("CHINESE", "区分材料中的事实陈述和作者观点。", "distinguish-fact-opinion"),
                RecallCase("MATH", "观察函数图象，写出单调递增区间。", "read-monotonicity-from-graph"),
                RecallCase("MATH", "用区间和符号语言准确写出函数的增减性。", "express-monotonicity-symbolically"),
                RecallCase("MATH", "从给出的函数图象读出最大值和最小值。", "read-extrema-from-graph"),
                RecallCase("ENGLISH", "第二段中的 it 指代什么内容？", "resolve-reference-by-cohesion"),
                RecallCase("ENGLISH", "选出文章主旨，而不是某个事实细节。", "separate-main-idea-details"),
                RecallCase("POLITICS", "指出材料中需要辨析的两个观点及其关系。", "identify-relationship-to-discriminate"),
                RecallCase("POLITICS", "用学科观点解释材料中的经济现象。", "explain-material-with-disciplinary-view"),
                RecallCase("HISTORY", "结合史料形成条件判断这则材料是否可信。", "evaluate-source-credibility"),
                RecallCase("HISTORY", "把事件放回当时的时间和空间背景中解释。", "interpret-in-time-space-context"),
                RecallCase("GEOGRAPHY", "先从示意图识别冷锋、低压和高压系统。", "identify-front-cyclone-anticyclone"),
                RecallCase("GEOGRAPHY", "结合天气图解释降水形成的原因。", "explain-weather-from-simple-map"),
                RecallCase("PHYSICS", "分析木块受到的力和它的运动情况。", "analyze-force-and-motion-state"),
                RecallCase("PHYSICS", "说明什么条件下可以忽略物体大小，把它看成质点。", "abstract-applicable-particle-model"),
                RecallCase("CHEMISTRY", "判断这种酸在水溶液中能否发生电离。", "judge-ionization-by-substance-state"),
                RecallCase("CHEMISTRY", "根据沉淀现象判断离子反应能否发生。", "infer-ionic-reaction-condition-from-evidence"),
                RecallCase("BIOLOGY", "根据 DNA 模板链写出转录形成的 RNA。", "transcribe-by-base-pairing"),
                RecallCase("BIOLOGY", "区分复制、转录和翻译时遗传信息的流向。", "distinguish-replication-transcription-translation-flow"),
            )

            var hitCount = 0
            cases.forEach { case ->
                val candidates = store.readSubjectKnowledgeRecallCandidates(
                    subject = case.subject,
                    searchFeatures = KnowledgeSearchFeatureExtractor.fromQuestion(case.question),
                    limit = 64,
                )
                val selected = KnowledgeContextRetriever.select(
                    candidates = candidates,
                    questionText = case.question,
                    limit = 64,
                )

                val hit = selected.any {
                    it.knowledgeNodeId.endsWith(":atomic:${case.expectedSlug}")
                }
                if (hit) hitCount += 1
                assertTrue("${case.subject} did not recall ${case.expectedSlug}", hit)
                assertTrue(selected.all { it.subject == case.subject })
                assertEquals(
                    "${case.subject} ranked the wrong fine-grained knowledge point first",
                    case.expectedSlug,
                    selected.first { it.granularity == KnowledgeNodeGranularity.ATOMIC.name }
                        .knowledgeNodeId
                        .substringAfterLast(':'),
                )
            }
            assertEquals(cases.size, hitCount)
            println(
                "bundled-knowledge-recall regression: hit=$hitCount/${cases.size}, " +
                    "cross-subject=0",
            )
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun largeSubjectRecallRemainsBoundedOnRoom() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val databaseName = "knowledge-retrieval-performance-${System.nanoTime()}.db"
        val store = StudyDatabaseFactory.open(context, databaseName)
        try {
            val topic = topic()
            val points = (1..KNOWLEDGE_POINT_COUNT).map(::point)
            importInBoundedBatches(store, topic, points)
            store.importKnowledgeNodeRelations(points.zipWithNext(::relation))
            val queryPlans = readQueryPlans(context, databaseName)
            assertTrue(
                "Knowledge search did not use an index: ${queryPlans.knowledgeSearch}",
                queryPlans.knowledgeSearch.any { line -> "USING" in line && "INDEX" in line },
            )
            assertTrue(
                "Knowledge relation lookup did not use an index: ${queryPlans.relations}",
                queryPlans.relations.any { line ->
                    "index_knowledge_node_relation_subject_dependent_knowledge_node_id" in line
                },
            )
            assertTrue(
                "Knowledge search unexpectedly scanned the feature table: ${queryPlans.knowledgeSearch}",
                queryPlans.knowledgeSearch.none { line -> "SCAN feature" in line },
            )

            suspend fun recall(): RecallMeasurement {
                var candidates = emptyList<KnowledgeNodeSeedRecord>()
                val candidateStarted = SystemClock.elapsedRealtimeNanos()
                candidates = store.readSubjectKnowledgeRecallCandidates(
                    subject = SUBJECT,
                    searchFeatures = KnowledgeSearchFeatureExtractor.fromQuestion(
                        "已知导函数符号变化，判断原函数的单调递增区间。",
                    ),
                    limit = 512,
                )
                val candidateMillis = elapsedMillis(candidateStarted)
                val relationStarted = SystemClock.elapsedRealtimeNanos()
                val relations = store.readKnowledgeNodeRelationsForDependents(
                    subject = SUBJECT,
                    dependentKnowledgeNodeIds = candidates.mapTo(hashSetOf()) {
                        it.knowledgeNodeId
                    },
                )
                val relationMillis = elapsedMillis(relationStarted)
                val supportingNodeStarted = SystemClock.elapsedRealtimeNanos()
                val supportingNodes = store.readKnowledgeNodesByIds(
                    relations.mapTo(hashSetOf()) { it.prerequisiteKnowledgeNodeId },
                )
                val supportingNodeMillis = elapsedMillis(supportingNodeStarted)
                val rankingStarted = SystemClock.elapsedRealtimeNanos()
                val selected = KnowledgeContextRetriever.select(
                    candidates = candidates + supportingNodes,
                    relations = relations,
                    questionText = "已知导函数符号变化，判断原函数的单调递增区间。",
                    limit = 64,
                )
                val rankingMillis = elapsedMillis(rankingStarted)
                return RecallMeasurement(
                    selected,
                    candidateMillis,
                    relationMillis,
                    supportingNodeMillis,
                    rankingMillis,
                )
            }

            recall()
            val elapsed = mutableListOf<Long>()
            var selected = emptyList<KnowledgeNodeSeedRecord>()
            val breakdowns = mutableListOf<String>()
            repeat(PERFORMANCE_SAMPLE_COUNT) {
                val started = SystemClock.elapsedRealtimeNanos()
                val measurement = recall()
                elapsed += elapsedMillis(started)
                selected = measurement.selected
                breakdowns += "${measurement.candidateMillis}/${measurement.relationMillis}/" +
                    "${measurement.supportingNodeMillis}/${measurement.rankingMillis}"
            }

            assertTrue(selected.any { it.knowledgeNodeId == pointId(KNOWLEDGE_POINT_COUNT) })
            assertTrue(selected.any { it.knowledgeNodeId == pointId(KNOWLEDGE_POINT_COUNT - 1) })
            assertTrue(selected.size <= 64)
            assertEquals(
                setOf(KnowledgeNodeVerificationStatus.CURATED.name, KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name),
                selected.mapTo(mutableSetOf(), KnowledgeNodeSeedRecord::verificationStatus),
            )
            assertTrue(
                "Room recall p95 was ${elapsed.percentile95()}ms; samples=$elapsed",
                elapsed.percentile95() < RECALL_P95_BUDGET_MILLIS,
            )
            println(
                "knowledge-room-recall benchmark: $KNOWLEDGE_POINT_COUNT points + " +
                    "${KNOWLEDGE_POINT_COUNT - 1} relations, runs=$elapsed, p95=${elapsed.percentile95()}ms, " +
                    "candidate/relation/support/ranking=$breakdowns, plans=$queryPlans",
            )

            store.commitProjection(masteryProjection(points))
            val masteryReadMillis = mutableListOf<Long>()
            var masteryCount = 0
            repeat(PERFORMANCE_SAMPLE_COUNT) {
                val started = SystemClock.elapsedRealtimeNanos()
                masteryCount = checkNotNull(
                    store.readCurrentLearnerSnapshot(PROJECTION_NAME, LEARNER_ID),
                ).snapshot.knowledgeMasteryStates.size
                masteryReadMillis += elapsedMillis(started)
            }
            assertEquals(KNOWLEDGE_POINT_COUNT, masteryCount)
            assertEquals(null, store.readCurrentLearnerSnapshot(PROJECTION_NAME, "another-learner"))
            assertTrue(
                "Mastery snapshot read p95 was ${masteryReadMillis.percentile95()}ms; samples=$masteryReadMillis",
                masteryReadMillis.percentile95() < MASTERY_READ_P95_BUDGET_MILLIS,
            )
            println(
                "mastery-room-read benchmark: $KNOWLEDGE_POINT_COUNT states, " +
                    "runs=$masteryReadMillis, p95=${masteryReadMillis.percentile95()}ms",
            )
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    private data class RecallMeasurement(
        val selected: List<KnowledgeNodeSeedRecord>,
        val candidateMillis: Long,
        val relationMillis: Long,
        val supportingNodeMillis: Long,
        val rankingMillis: Long,
    )

    private data class QueryPlans(
        val knowledgeSearch: List<String>,
        val relations: List<String>,
    )

    private data class RecallCase(
        val subject: String,
        val question: String,
        val expectedSlug: String,
    )

    private fun masteryProjection(points: List<KnowledgeNodeSeedRecord>): ProjectionCommit {
        val checkpoint = ProjectionCheckpoint.empty(PROJECTOR_VERSION)
        val snapshot = LearnerSnapshot(
            learnerId = LEARNER_ID,
            knowledgeMasteryStates = points.associate { point ->
                point.knowledgeNodeId to KnowledgeMasteryState(
                    knowledgeNodeId = point.knowledgeNodeId,
                    masteryScore = 0.0,
                    conservativeMasteryScore = 0.0,
                    evidenceMass = 0.0,
                    status = MasteryStatus.UNKNOWN,
                    calibrationSupport = CalibrationSupport.UNKNOWN,
                    projectorVersion = PROJECTOR_VERSION,
                    checkpointSequence = 0,
                )
            },
            checkpoint = checkpoint,
            generatedAtEpochMillis = REVIEWED_AT,
        )
        return ProjectionCommit(
            projectionName = PROJECTION_NAME,
            learnerId = LEARNER_ID,
            expectedPreviousCheckpoint = 0,
            expectedPreviousStateVersion = 0,
            mode = ProjectionCommitMode.FULL_REPLAY,
            knownLedgerHeadSequence = 0,
            consumedLedgerEvents = emptyList(),
            presentationProjectionStates = emptyMap(),
            snapshot = snapshot,
        )
    }

    private fun readQueryPlans(
        context: android.content.Context,
        databaseName: String,
    ): QueryPlans = SQLiteDatabase.openDatabase(
        context.getDatabasePath(databaseName).path,
        null,
        SQLiteDatabase.OPEN_READONLY,
    ).use { database ->
        QueryPlans(
            knowledgeSearch = database.queryPlan(
                """
                SELECT node.*
                FROM knowledge_search_feature AS feature
                INNER JOIN knowledge_node AS node
                  ON node.knowledge_node_id = feature.knowledge_node_id
                WHERE feature.subject = ?
                  AND feature.search_feature IN (?, ?)
                  AND node.verification_status IN ('CURATED', 'SOURCE_GROUNDED')
                GROUP BY node.knowledge_node_id
                ORDER BY COUNT(DISTINCT feature.search_feature) DESC
                LIMIT 512
                """.trimIndent(),
                arrayOf(SUBJECT, "导数", "单调"),
            ),
            relations = database.queryPlan(
                """
                SELECT *
                FROM knowledge_node_relation
                WHERE subject = ?
                  AND dependent_knowledge_node_id IN (?, ?)
                """.trimIndent(),
                arrayOf(SUBJECT, pointId(KNOWLEDGE_POINT_COUNT), pointId(KNOWLEDGE_POINT_COUNT - 1)),
            ),
        )
    }

    private fun SQLiteDatabase.queryPlan(
        sql: String,
        arguments: Array<String>,
    ): List<String> = rawQuery("EXPLAIN QUERY PLAN $sql", arguments).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.getString(3))
        }
    }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / 1_000_000

    private fun List<Long>.percentile95(): Long {
        require(isNotEmpty())
        val sorted = sorted()
        val index = ((sorted.size * 95 + 99) / 100 - 1).coerceIn(sorted.indices)
        return sorted[index]
    }

    private suspend fun importInBoundedBatches(
        store: com.tingyun.smartmistakebook.core.database.StudyDatabasePort,
        topic: KnowledgeNodeSeedRecord,
        points: List<KnowledgeNodeSeedRecord>,
    ) {
        val firstNodes = listOf(topic) + points.take(FIRST_POINT_BATCH_SIZE)
        store.importKnowledgeBase(
            sources = listOf(source()),
            nodes = firstNodes,
            bindings = firstNodes.map(::binding),
        )
        points.drop(FIRST_POINT_BATCH_SIZE).chunked(MAX_IMPORT_SIZE).forEach { batch ->
            store.importKnowledgeBase(
                sources = emptyList(),
                nodes = batch,
                bindings = batch.map(::binding),
            )
        }
    }

    private fun source() = KnowledgeSourceSeedRecord(
        sourceId = SOURCE_ID,
        subject = SUBJECT,
        sourceType = KnowledgeSourceType.MANUAL_RESEARCH.name,
        title = "经核验的高中数学知识目录",
        publisher = "授权教研机构",
        edition = null,
        sourceUri = "https://example.edu/math/knowledge-index",
        licenseStatus = KnowledgeSourceLicenseStatus.REFERENCE_ONLY.name,
        contentFingerprint = "C".repeat(64),
        importedAtEpochMillis = REVIEWED_AT,
    )

    private fun topic() = KnowledgeNodeSeedRecord(
        knowledgeNodeId = TOPIC_ID,
        stableCode = "benchmark:math:topic:function",
        subject = SUBJECT,
        displayName = "函数性质",
        parentKnowledgeNodeId = null,
        taxonomyVersion = TAXONOMY_VERSION,
        createdAtEpochMillis = REVIEWED_AT,
        canonicalName = "函数性质",
        nodeKind = KnowledgeNodeKind.TOPIC.name,
        granularity = KnowledgeNodeGranularity.TOPIC.name,
        verificationStatus = KnowledgeNodeVerificationStatus.CURATED.name,
    )

    private fun point(index: Int) = KnowledgeNodeSeedRecord(
        knowledgeNodeId = pointId(index),
        stableCode = "benchmark:math:point:${index.toString().padStart(4, '0')}",
        subject = SUBJECT,
        displayName = if (index == KNOWLEDGE_POINT_COUNT) {
            "根据导数符号判断函数单调区间"
        } else {
            "无关分类条目${index.toString().padStart(4, '0')}"
        },
        parentKnowledgeNodeId = TOPIC_ID,
        taxonomyVersion = TAXONOMY_VERSION,
        createdAtEpochMillis = REVIEWED_AT,
        canonicalName = if (index == KNOWLEDGE_POINT_COUNT) {
            "根据导数符号判断函数单调区间"
        } else {
            "无关分类条目${index.toString().padStart(4, '0')}"
        },
        nodeKind = KnowledgeNodeKind.REASONING.name,
        granularity = KnowledgeNodeGranularity.ATOMIC.name,
        aliases = if (index == KNOWLEDGE_POINT_COUNT) setOf("导数与单调性") else emptySet(),
        boundaryMarkdown = "只用于本次检索压力测试。",
        verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name,
    )

    private fun binding(node: KnowledgeNodeSeedRecord) = KnowledgeNodeSourceBindingSeedRecord(
        knowledgeNodeId = node.knowledgeNodeId,
        sourceId = SOURCE_ID,
        sourceLocator = "函数性质目录",
        derivationNote = "人工核验后录入。",
        reviewedAtEpochMillis = REVIEWED_AT,
    )

    private fun relation(
        prerequisite: KnowledgeNodeSeedRecord,
        dependent: KnowledgeNodeSeedRecord,
    ): KnowledgeNodeRelationRecord {
        val draft = KnowledgeNodeRelationRecord(
            relationId = "pending",
            subject = SUBJECT,
            prerequisiteKnowledgeNodeId = prerequisite.knowledgeNodeId,
            dependentKnowledgeNodeId = dependent.knowledgeNodeId,
            relationType = StudyDbValue.KnowledgeRelationType.PREREQUISITE_OF,
            sourceId = SOURCE_ID,
            sourceLocator = "函数性质目录",
            reviewedAtEpochMillis = REVIEWED_AT,
        )
        return draft.copy(relationId = KnowledgeNodeRelationContract.expectedId(draft))
    }

    private fun pointId(index: Int): String = "kb:benchmark:math:point:${index.toString().padStart(4, '0')}"

    private companion object {
        const val SUBJECT = "MATH"
        const val SOURCE_ID = "source:benchmark:math"
        const val TOPIC_ID = "kb:benchmark:math:topic:function"
        const val TAXONOMY_VERSION = "benchmark-v1"
        const val REVIEWED_AT = 1_000L
        const val KNOWLEDGE_POINT_COUNT = 20_000
        const val MAX_IMPORT_SIZE = 4_096
        const val FIRST_POINT_BATCH_SIZE = MAX_IMPORT_SIZE - 1
        const val PROJECTION_NAME = "knowledge-performance-v1"
        const val PROJECTOR_VERSION = "knowledge-performance-projector-v1"
        const val LEARNER_ID = "learner:knowledge-performance"
        const val PERFORMANCE_SAMPLE_COUNT = 10
        const val RECALL_P95_BUDGET_MILLIS = 150
        const val MASTERY_READ_P95_BUDGET_MILLIS = 250
    }
}
