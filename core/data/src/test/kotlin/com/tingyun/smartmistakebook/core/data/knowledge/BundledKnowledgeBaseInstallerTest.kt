package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.data.study.FakeStudyDatabasePort
import com.tingyun.smartmistakebook.core.database.ContentInstallStateRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeContentUpdateCommand
import com.tingyun.smartmistakebook.core.database.KnowledgeContentUpdateResult
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * install() 的三条路径（首装 / 戳变 / 戳同）+ 驻留释放的观测面。
 *
 * 对应验收（R4a，决策台账 D13）：
 * - 戳同 → 快路径：零解析、零内容写入、零戳重写。错题归类路径
 *   （`RoomMistakeOrganizationRepository` 每次归类前都调 install()）靠它零解析；
 * - 首装（无进度行）/ 戳变 → 全量路径：解析 + 逐包调和 + 写戳。
 *
 * fake 的 `applyKnowledgeContentUpdate` 按"调和即全部接收"实现，对这些用例够用：
 * 这里测的是 install 的顺序与快路径判定，不是逐对象调和行为
 * （逐对象行为由 androidTest 的 `BundledContentReconciliationInstrumentedTest` 钉死）。
 */
class BundledKnowledgeBaseInstallerTest {

    private val countingPort = CountingInstallPort(FakeStudyDatabasePort())

    @Before
    fun setUp() {
        // 冷缓存：快路径用例要靠解析计数证明"一个字符没读"，全量路径用例要观察到
        // 解析真的发生——缓存温热会让两类断言都失真。
        BundledKnowledgePackResources.clear()
    }

    @After
    fun tearDown() {
        BundledKnowledgePackResources.clear()
    }

    @Test
    fun `first install with no progress row takes the full path and writes the stamp`() = runBlocking {
        val manifest = BundledKnowledgePackResources.loadManifestOnly()
        assertNotNull("update-manifest 应在 classpath 上", manifest)
        val parseCountBefore = BundledKnowledgePackResources.fullParseCount

        BundledKnowledgeBaseInstaller.install(countingPort)

        assertEquals("全量路径触发恰好一次全量解析", parseCountBefore + 1, BundledKnowledgePackResources.fullParseCount)
        assertEquals("全量路径调和两个随包 pack（2020 样例 + 2025 四科）", 2, countingPort.contentUpdateCalls)
        assertEquals(
            "进度戳写入当前内容版本",
            manifest!!.contentVersion,
            countingPort.readContentInstallState(manifest.packId)?.contentVersion,
        )
    }

    @Test
    fun `matching stamp takes the fast path with zero parse and zero writes`() = runBlocking {
        val manifest = BundledKnowledgePackResources.loadManifestOnly()!!
        // 模拟"上次调和完整跑完"：进度戳 == manifest 戳
        countingPort.recordContentInstallState(
            ContentInstallStateRecord(
                packId = manifest.packId,
                contentVersion = manifest.contentVersion,
                appliedAtEpochMillis = 1L,
            ),
        )
        val parseCountBefore = BundledKnowledgePackResources.fullParseCount

        BundledKnowledgeBaseInstaller.install(countingPort)

        // 归类路径（每次归类前 install()）依赖这条：戳一致 = 零解析
        assertEquals("快路径不触碰驻留 holder", parseCountBefore, BundledKnowledgePackResources.fullParseCount)
        assertEquals("快路径零内容更新命令", 0, countingPort.contentUpdateCalls)
        assertEquals("快路径不重写进度戳", 1, countingPort.stateRecordWrites.size)
    }

    @Test
    fun `stale stamp takes the full path and rewrites the stamp`() = runBlocking {
        val manifest = BundledKnowledgePackResources.loadManifestOnly()!!
        countingPort.recordContentInstallState(
            ContentInstallStateRecord(
                packId = manifest.packId,
                contentVersion = "stale-0000000000000000",
                appliedAtEpochMillis = 1L,
            ),
        )
        val parseCountBefore = BundledKnowledgePackResources.fullParseCount

        BundledKnowledgeBaseInstaller.install(countingPort)

        assertEquals("全量路径触发恰好一次全量解析", parseCountBefore + 1, BundledKnowledgePackResources.fullParseCount)
        assertEquals("全量路径调和两个随包 pack", 2, countingPort.contentUpdateCalls)
        assertEquals(
            "进度戳重写为当前内容版本",
            manifest.contentVersion,
            countingPort.stateRecordWrites.last().contentVersion,
        )
    }

    @Test
    fun `loadManifestOnly parses the manifest without triggering the full pack parse`() = runBlocking {
        val parseCountBefore = BundledKnowledgePackResources.fullParseCount

        val manifest = BundledKnowledgePackResources.loadManifestOnly()

        assertNotNull("manifest 应解析成功", manifest)
        assertEquals("单独读 manifest 不触发整包解析", parseCountBefore, BundledKnowledgePackResources.fullParseCount)
    }

    @Test
    fun `release then load refills the holder instead of serving an empty pack list`() = runBlocking {
        // releaseResidentPacks() 把 holder 置空后，下一次全量路径必须重新解析出**完整**包列表：
        // 若 clear 留下空列表而非 null，全量路径会拿空列表调和 = 静默清空知识库。
        BundledKnowledgeBaseInstaller.releaseResidentPacks()
        val parseCountBefore = BundledKnowledgePackResources.fullParseCount

        val packs = BundledKnowledgePackResources.load()

        assertEquals("释放后重新解析恰好一次", parseCountBefore + 1, BundledKnowledgePackResources.fullParseCount)
        assertTrue("释放后的重新填充不得为空列表", packs.isNotEmpty())
    }

    /** 计数面：内容更新命令数 + 进度戳写入。其余面委托给 study 侧的完整 fake。 */
    private class CountingInstallPort(val delegate: FakeStudyDatabasePort) :
        StudyDatabasePort by delegate {
        var contentUpdateCalls = 0
            private set
        val stateRecordWrites = mutableListOf<ContentInstallStateRecord>()

        override suspend fun applyKnowledgeContentUpdate(
            command: KnowledgeContentUpdateCommand,
        ): KnowledgeContentUpdateResult {
            contentUpdateCalls++
            return delegate.applyKnowledgeContentUpdate(command)
        }

        override suspend fun recordContentInstallState(record: ContentInstallStateRecord) {
            stateRecordWrites += record
            delegate.recordContentInstallState(record)
        }
    }
}
