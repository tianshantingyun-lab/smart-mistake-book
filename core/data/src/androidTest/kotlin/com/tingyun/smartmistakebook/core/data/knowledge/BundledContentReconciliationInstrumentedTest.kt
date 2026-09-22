package com.tingyun.smartmistakebook.core.data.knowledge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真实随包内容必须**一条都不跳过**地调和进去。
 *
 * 这条同时钉两件事：
 * 1. 调和在**真实规模**上跑得通（当前约 3600 节点 / 约 27000 材料，随内容入库持续增长），
 *    而不是只在小夹具上成立；
 * 2. 内容侧没有"落地就会被挡"的对象。逐对象跳过修掉了"一条坏数据挡住整包"，但静默少更新
 *    比整包停摆更难发现——所以跳过数必须被断言、被看见。
 *
 * KD-15 就是这个形态的历史：80 条材料的 `reviewedAt` 比来源 `importedAt` 早几毫秒，整批
 * 材料被挡在门外、每次启动弹横幅。这条用例会在那类内容缺陷重新出现时立刻变红。
 *
 * **位置说明**：它必须住在 `core:data` 的 androidTest，不能住 `core:database`——
 * 安装器与内容包加载器都在 core:data，而 `core:database` 不依赖它（依赖方向是单向的）。
 */
@RunWith(AndroidJUnit4::class)
class BundledContentReconciliationInstrumentedTest {

    @Test
    fun bundledContentReconcilesWithoutSkippingAnything() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "kb-bundled-reconcile-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val store = StudyDatabaseFactory.open(context, databaseName)
        try {
            BundledKnowledgeBaseInstaller.install(store)

            val state = store.readContentInstallState(BUNDLED_PACK_ID)
            requireNotNull(state) { "随包内容跑完调和后必须留下进度行" }
            assertTrue(
                "内容戳应来自随包台账（不是空串），否则快速路径永不生效：$state",
                state.contentVersion.isNotEmpty(),
            )
            assertEquals(
                "有对象被逐条校验挡掉了，按进度表里的 skipped_detail 定位：" +
                    state.skippedDetail,
                0,
                state.skippedCount,
            )

            // 再跑一次必须走**快速路径**：内容没变就不该重新调和。
            // 判据用 `appliedAtEpochMillis`——全量调和会重写进度行（时间戳变），
            // 快速路径直接返回（时间戳不变）。拿 contentVersion 跟自己比是恒真的，测不出东西。
            BundledKnowledgeBaseInstaller.install(store)
            val after = store.readContentInstallState(BUNDLED_PACK_ID)
            assertEquals(
                "第二次调和应走快速路径、不该重写进度行",
                state.appliedAtEpochMillis,
                after?.appliedAtEpochMillis,
            )
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    private companion object {
        const val BUNDLED_PACK_ID = "moe-2025-four-subjects-v1"
    }
}
