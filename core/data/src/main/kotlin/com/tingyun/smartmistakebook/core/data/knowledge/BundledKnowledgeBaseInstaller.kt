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
        val installStartedAt = System.nanoTime()
        // 快路径（D13/R4a）：manifest 是 111KB 小文件，单独解析、不触发整包解析；
        // 先对进度戳——记录行的 packId 即按 manifest.packId 查询（DAO `WHERE pack_id = ?`），
        // contentVersion 一致 ⟺ 上一次调和完整跑完且随包内容未变（WP2/R1 前提：
        // 戳原子可信、仅 promote 刷新）。命中则**跳过全量解析与调和**，不触碰驻留 holder。
        //
        // 已知边界（登记于 `docs/kb-architecture-refactor-decisions-2026-09-21.md` §6）：
        // 信戳不验包——戳一致但 APK 内 JSON 被改动不会被发现；威胁模型内无攻击者
        // （APK 受签名保护，"每次都验"本身也只验 APK 自带资源），故接受而不加机制。
        val manifest = BundledKnowledgePackResources.updateManifest
        if (manifest != null) {
            val recorded = database.readContentInstallState(manifest.packId)
            if (recorded != null && recorded.contentVersion == manifest.contentVersion) {
                // 常驻观测：install 全程耗时。before/after 启动基线以它对比
                // （`.jez/artifacts/r4a-startup-baseline-2026-09-22.md`）。
                android.util.Log.d(
                    "KnowledgeInstall",
                    "install finished in ${elapsedMillis(installStartedAt)} ms " +
                        "(fast path: content stamp matches, no parse, no reconcile)",
                )
                return@withLock
            }
        }
        BundledKnowledgePackResources.load().forEach { pack ->
            pack.validate()
            reconcile(database, pack, manifest?.takeIf { it.packId == pack.packId })
        }
        android.util.Log.d(
            "KnowledgeInstall",
            "install finished in ${elapsedMillis(installStartedAt)} ms (full path: parse + reconcile)",
        )
    }

    /**
     * 释放驻留的包对象图，供进程编排侧（Application）在 install() 成功返回后调用：
     * 那一刻数据已在 Room，对象图使命结束。
     *
     * 安全前提：全仓唯一生产消费方是本 installer（`BundledKnowledgePackResources`
     * 的 grep 核实；测试不算生产路径）。释放后，后续 install() 命中快路径时零解析；
     * 若戳不一致（仅 App 升级后首次），全量解析会重新填充，调用方再释放一次。
     * 可重复调用，空 holder 上是 no-op。
     */
    fun releaseResidentPacks() {
        BundledKnowledgePackResources.clear()
    }

    private fun elapsedMillis(startedAtNano: Long): Long =
        (System.nanoTime() - startedAtNano) / 1_000_000

    private suspend fun reconcile(
        database: StudyDatabasePort,
        pack: KnowledgeBasePack,
        manifest: KnowledgeUpdateManifest?,
    ) {
        val contentVersion = manifest?.contentVersion.orEmpty()

        // 逐包进度锚点（纵深）：上次调和**完整跑完**（进度行最后写，所以版本一致 ⟹ 跑完了），
        // 且包没变 → 跳过本包的差分和写戳。主闸已上移到 `install()`（manifest 先读、
        // 戳一致则整次 install 零解析），这里覆盖"install 级检查之后状态又变"与
        // 2020 样例包这类无 manifest（contentVersion 为空）的包：它们照旧逐次差分。
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
