package com.tingyun.smartmistakebook.core.data.knowledge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.SubjectKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledKnowledgeBaseInstallerInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: StudyDatabasePort
    private lateinit var databaseName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "bundled-knowledge-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        database = StudyDatabaseFactory.open(context, databaseName)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun installsEverySupportedSubjectWithGroundedAtomsAndIsIdempotent() = runBlocking {
        BundledKnowledgeBaseInstaller.install(database)
        BundledKnowledgeBaseInstaller.install(database)

        SUPPORTED_SUBJECTS.forEach { subject ->
            val nodes = database.readSubjectKnowledgeNodes(subject.name, limit = 256)
            val topics = nodes.filter { it.granularity == KnowledgeNodeGranularity.TOPIC.name }
            val atoms = nodes.filter { it.granularity == KnowledgeNodeGranularity.ATOMIC.name }

            assertTrue("$subject needs a visible topic", topics.isNotEmpty())
            assertTrue("$subject needs grounded atomic abilities", atoms.isNotEmpty())
            assertTrue(atoms.all {
                it.verificationStatus == KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name
            })
            atoms.forEach { atom ->
                val parent = topics.singleOrNull { it.knowledgeNodeId == atom.parentKnowledgeNodeId }
                assertNotNull("${atom.canonicalName} needs a same-subject topic parent", parent)
            }
            assertEquals(subject.name, nodes.map { it.subject }.distinct().single())
        }

        val coverage = database.observeReviewedKnowledgeCoverage().first()
        assertEquals(SUPPORTED_SUBJECTS.size, coverage.size)
        // 9 科都有已审 topic 与原子知识点（moe-2020 样本 5 科 + moe-2025 四科大包）
        coverage.forEach { record ->
            assertTrue("${record.subject} needs a visible topic", record.topicCount > 0)
            assertTrue(
                "${record.subject} needs grounded atomic abilities",
                record.atomicKnowledgeCount > 0,
            )
            assertTrue("${record.subject} needs a reviewed source", record.reviewedSourceCount > 0)
        }
        assertTrue(coverage.sumOf { it.atomicKnowledgeCount } >= 19)
        val relations = SUPPORTED_SUBJECTS.flatMap { subject ->
            database.readSubjectKnowledgeNodeRelations(subject.name, limit = 64)
        }
        assertTrue(relations.size >= 2)
        assertTrue(relations.all { it.prerequisiteKnowledgeNodeId != it.dependentKnowledgeNodeId })

        val mathNodes = database.readSubjectKnowledgeNodes(SubjectKind.MATH.name, limit = 256)
        val mathNodeIds = mathNodes.mapTo(hashSetOf()) { it.knowledgeNodeId }
        val teachingSupport = database.readKnowledgeTeachingMaterialsForNodes(
            subject = SubjectKind.MATH.name,
            knowledgeNodeIds = mathNodeIds,
            limit = 8,
        )
        assertTrue("math teaching support should be retrievable", teachingSupport.isNotEmpty())
        val tutorReferences = RoomTutorTeachingReferenceRepository(database).referencesFor(
            subject = SubjectKind.MATH.name,
            knowledgeNodeIds = mathNodeIds,
        )
        assertTrue("math tutor references should be retrievable", tutorReferences.isNotEmpty())

        val physicsNodes = database.readSubjectKnowledgeNodes(
            subject = SubjectKind.PHYSICS.name,
            limit = 256,
        )
        assertTrue(
            "physics tutor references should be retrievable after the large-pack import",
            RoomTutorTeachingReferenceRepository(database).referencesFor(
                subject = SubjectKind.PHYSICS.name,
                knowledgeNodeIds = physicsNodes.mapTo(hashSetOf()) { it.knowledgeNodeId },
            ).isNotEmpty(),
        )
    }

    private companion object {
        val SUPPORTED_SUBJECTS = SubjectKind.entries - SubjectKind.GENERAL
    }
}
