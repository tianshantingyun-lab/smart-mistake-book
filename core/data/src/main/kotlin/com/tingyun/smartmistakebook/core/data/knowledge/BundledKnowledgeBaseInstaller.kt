package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.ContentInstallStateRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeContentUpdateCommand
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Installs reviewed curriculum maps and optional teaching support.
 *
 * Teaching support may contain methods, explanations, and worked examples. It remains physically
 * separate from practice units and therefore cannot become a question bank or review item.
 *
 * **本对象是发布后更新知识库的唯一通道**（设计见 `docs/research/kb-update-design-2026-09-19.md`）。
 *
 * 它消灭的失败：改前这里是"逐行相等否则整包拒绝"——库里一行都没有就导入，否则要求节点、
 * 来源、绑定、材料**四样逐行完全相等**，不等就抛。于是发布后哪怕只改一个节点名都会让知识库
 * 整包停摆、横幅常驻（KD-15 就是这个形态：80 条材料的时间戳倒挂，把另外一万条一起挡在门外）。
 *
 * 现在改成**调和**：逐对象判定插入 / 原地更新 / 退役，坏对象只跳过自己并记进进度表。
 * 崩溃安全不靠大事务，而靠差分的幂等性——任一步崩掉，下次启动重跑同一份差分即收敛。
 */
object BundledKnowledgeBaseInstaller {
    private val installMutex = Mutex()

    /** 进度表里保留的"被跳过对象"上限。跳过要可见，但不能让一次坏包把表撑爆。 */
    private const val MAX_SKIPPED_DETAIL = 50

    suspend fun install(database: StudyDatabasePort) = installMutex.withLock {
        val manifest = BundledKnowledgePackResources.updateManifest
        BundledKnowledgePackResources.load().forEach { pack ->
            pack.validate()
            reconcile(database, pack, manifest?.takeIf { it.packId == pack.packId })
        }
    }

    private suspend fun reconcile(
        database: StudyDatabasePort,
        pack: KnowledgeBasePack,
        manifest: KnowledgeUpdateManifest?,
    ) {
        val contentVersion = manifest?.contentVersion.orEmpty()

        // 快速路径：上次调和**完整跑完**（进度行最后写，所以版本一致 ⟺ 跑完了），且包没变。
        // 没有它，每次启动都要读约 2600 个节点加约 11000 条材料来确认"什么都没变"。
        if (contentVersion.isNotEmpty()) {
            val recorded = database.readContentInstallState(pack.packId)
            if (recorded?.contentVersion == contentVersion) return
        }

        val result = database.applyKnowledgeContentUpdate(
            KnowledgeContentUpdateCommand(
                packId = pack.packId,
                contentVersion = contentVersion,
                nodes = pack.nodes,
                sources = pack.sources,
                nodeSourceBindings = pack.bindings,
                relations = pack.relations,
                materials = pack.teachingMaterials,
                materialBindings = pack.teachingMaterialBindings,
                nodeRetirements = manifest?.retirementByNodeId.orEmpty(),
                teachingSources = pack.teachingSources,
            ),
        )

        database.recordContentInstallState(
            ContentInstallStateRecord(
                packId = pack.packId,
                contentVersion = contentVersion,
                appliedAtEpochMillis = System.currentTimeMillis(),
                skippedCount = result.skipped.size,
                skippedDetail = result.skipped.take(MAX_SKIPPED_DETAIL).joinToString("\n"),
            ),
        )

        if (result.skipped.isNotEmpty()) {
            // 跳过**必须可见**：逐对象跳过修掉了"一条坏数据挡住整包"，但如果不说，
            // 就会退化成"静默少更新"——那比整包停摆更难发现。
            android.util.Log.w(
                "KnowledgeReconcile",
                "pack ${pack.packId} skipped ${result.skipped.size} object(s); " +
                    "first: ${result.skipped.first()}",
            )
        }
    }
}
