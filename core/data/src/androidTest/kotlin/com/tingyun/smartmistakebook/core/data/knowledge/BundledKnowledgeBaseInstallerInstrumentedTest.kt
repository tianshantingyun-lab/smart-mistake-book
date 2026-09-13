package com.tingyun.smartmistakebook.core.data.knowledge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.KnowledgeSourceSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialRecord
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
            limit = 4,
        )
        assertTrue("math tutor references should be retrievable", tutorReferences.isNotEmpty())

        val physicsNodes = database.readSubjectKnowledgeNodes(
            SubjectKind.PHYSICS.name,
            limit = 256,
        )
        assertTrue(
            "physics tutor references should be retrievable after the large-pack import",
            RoomTutorTeachingReferenceRepository(database).referencesFor(
                subject = SubjectKind.PHYSICS.name,
                knowledgeNodeIds = physicsNodes.mapTo(hashSetOf()) { it.knowledgeNodeId },
                limit = 4,
            ).isNotEmpty(),
        )
    }

    /**
     * 审计 N-10：教学支持按批次拆成多个事务导入（bundled sidecar 单份 2048 条，超过 DB 契约
     * 的单批上限，所以分成 2000 条一批）。进程若在两个批次之间被杀，留下的是「前 2000 条已提交、
     * 其余不存在」——**半装**。
     *
     * 修复前的这一状态会永久卡死：下一次 `install()` 走进校验分支，`require` 抛
     * 「incomplete or conflicting teaching support」，而 `install()` 每次启动都跑一遍，
     * 于是每次启动都抛同一句，没有任何修复路径。修复后它应当被**下一次安装自动补齐**。
     *
     * 夹具必须是**真的半装**，而不是"没装"：先按真实顺序把知识点装上
     * （`installPack` 就是先 `importKnowledgeBase` 再装教学支持——教学材料的绑定校验要求
     * 被绑定的知识点已经存在），然后只导入第一个批次。
     */
    @Test
    fun aHalfInstalledTeachingSidecarIsHealedByTheNextInstall() = runBlocking {
        val pack = BundledKnowledgePackResources.load()
            .single { it.teachingMaterials.size > HALF_INSTALL_BATCH }
        val allMaterialIds = pack.teachingMaterials
            .mapTo(hashSetOf(), KnowledgeTeachingMaterialRecord::materialId)
        val firstBatch = pack.teachingMaterials.take(HALF_INSTALL_BATCH)
        val batchIds = firstBatch.mapTo(hashSetOf(), KnowledgeTeachingMaterialRecord::materialId)
        val sourceById = pack.teachingSources.associateBy(KnowledgeSourceSeedRecord::sourceId)

        database.importKnowledgeBase(pack.sources, pack.nodes, pack.bindings)
        database.importKnowledgeTeachingMaterials(
            materials = firstBatch,
            bindings = pack.teachingMaterialBindings.filter { it.materialId in batchIds },
            sources = firstBatch.mapTo(linkedSetOf()) { it.sourceId }.mapNotNull { sourceById[it] },
        )

        assertEquals(
            "夹具必须是「装了一半」而不是「没装」：${pack.packId}",
            HALF_INSTALL_BATCH,
            database.readKnowledgeTeachingMaterialsByIds(allMaterialIds).size,
        )

        BundledKnowledgeBaseInstaller.install(database)

        val healed = database.readKnowledgeTeachingMaterialsByIds(allMaterialIds)
            .associateBy(KnowledgeTeachingMaterialRecord::materialId)
        assertEquals(
            "下一次安装必须把缺的那半补上，而不是永远停在「不完整」上（审计 N-10）",
            pack.teachingMaterials.size,
            healed.size,
        )
        // 逐条比载荷，而不只是数数：数量对得上但内容被换成另一批同样是失败。
        assertEquals(
            "补上的必须就是原来那批材料本身",
            pack.teachingMaterials.associateBy(KnowledgeTeachingMaterialRecord::materialId),
            healed,
        )
        // 尾部那一条来自**最后一个批次**：只有真的把整份 sidecar 走完它才可能存在，
        // 所以它专门证伪"只补了缺的那一批之后又停在半路"。
        assertTrue(
            "最后一条材料必须已被补上：${pack.teachingMaterials.last().materialId}",
            healed.containsKey(pack.teachingMaterials.last().materialId),
        )
        // 幂等：补齐之后再装一次不得改变任何东西（否则"修复"自身会变成新的抖动源）。
        BundledKnowledgeBaseInstaller.install(database)
        assertEquals(
            "补齐后再装一次必须逐条相等",
            healed,
            database.readKnowledgeTeachingMaterialsByIds(allMaterialIds)
                .associateBy(KnowledgeTeachingMaterialRecord::materialId),
        )
    }

    private companion object {
        val SUPPORTED_SUBJECTS = SubjectKind.entries - SubjectKind.GENERAL

        /** 与 `installTeachingMaterials` 的分批上限一致：夹具要正好停在批次边界上。 */
        const val HALF_INSTALL_BATCH = 2_000
    }
}
