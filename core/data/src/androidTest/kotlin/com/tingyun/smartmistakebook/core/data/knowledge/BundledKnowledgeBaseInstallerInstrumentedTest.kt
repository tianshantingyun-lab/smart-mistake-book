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
            val nodes = database.readSubjectKnowledgeNodes(subject.name, limit = 64)
            val topics = nodes.filter { it.granularity == KnowledgeNodeGranularity.TOPIC.name }
            val atoms = nodes.filter { it.granularity == KnowledgeNodeGranularity.ATOMIC.name }

            assertTrue("$subject needs a visible topic", topics.isNotEmpty())
            assertTrue("$subject needs grounded atomic abilities", atoms.size >= 2)
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
        assertEquals(19, coverage.sumOf { it.atomicKnowledgeCount })
        assertEquals(SUPPORTED_SUBJECTS.size, coverage.sumOf { it.topicCount })
        assertEquals(SUPPORTED_SUBJECTS.size, coverage.sumOf { it.reviewedSourceCount })
        val relations = SUPPORTED_SUBJECTS.flatMap { subject ->
            database.readSubjectKnowledgeNodeRelations(subject.name, limit = 64)
        }
        assertEquals(2, relations.size)
        assertTrue(relations.all { it.prerequisiteKnowledgeNodeId != it.dependentKnowledgeNodeId })

        val mathNodes = database.readSubjectKnowledgeNodes(SubjectKind.MATH.name, limit = 64)
        val teachingSupport = database.readKnowledgeTeachingMaterialsForNodes(
            subject = SubjectKind.MATH.name,
            knowledgeNodeIds = mathNodes.mapTo(hashSetOf()) { it.knowledgeNodeId },
            limit = 8,
        )
        assertEquals(2, teachingSupport.size)
        assertTrue(
            teachingSupport.any {
                it.materialType == KnowledgeTeachingMaterialType.WORKED_EXAMPLE.name
            },
        )
        val tutorReferences = RoomTutorTeachingReferenceRepository(database).referencesFor(
            subject = SubjectKind.MATH.name,
            knowledgeNodeIds = mathNodes.mapTo(hashSetOf()) { it.knowledgeNodeId },
            limit = 4,
        )
        assertEquals(2, tutorReferences.size)
        assertTrue(
            tutorReferences.any { reference ->
                reference.materialType == KnowledgeTeachingMaterialType.WORKED_EXAMPLE &&
                    reference.contentMarkdown.contains("例如") &&
                    reference.contentMarkdown.contains("递增")
            },
        )

        val physicsNodes = database.readSubjectKnowledgeNodes(
            SubjectKind.PHYSICS.name,
            limit = 64,
        )
        assertTrue(
            RoomTutorTeachingReferenceRepository(database).referencesFor(
                subject = SubjectKind.PHYSICS.name,
                knowledgeNodeIds = physicsNodes.mapTo(hashSetOf()) { it.knowledgeNodeId },
                limit = 4,
            ).isEmpty(),
        )
    }

    private companion object {
        val SUPPORTED_SUBJECTS = SubjectKind.entries - SubjectKind.GENERAL
    }
}
