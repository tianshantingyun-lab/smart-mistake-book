package com.tingyun.smartmistakebook.core.domain

/**
 * 知识点「是否已具备」的唯一权威（spec §2.9）：`readyToLearn(k) = ∀p∈prereq(k):
 * masteryScore_p ≥ τ_ready`，`prereqGap(k) = max(0, τ_ready − min_p masteryScore_p)`。
 *
 * τ_ready = 0.6 是**工程先验**，不是实证标定值：ALEKS 公开材料里找不到"前置掌握多少分才
 * 解锁下一主题"的具体数值（`docs/research/leech-remediation-research.md` §4）。因此禁止在
 * 代码注释或产品文案里写成"科学研究表明"。
 *
 * 为什么单独成一处：这条规则有两个消费方——排程侧按 gap 给候选降权并打 `PREREQ_GAP`
 * 理由（`ReviewPlannerV2`），会话侧按同一个 gap 决定给谁注入前置补救材料
 * （[PrerequisiteRemediationPolicy]）。两处各写一遍阈值与取值口径，就会分裂成"排程认为缺
 * 前置、会话却认为不缺"——同一规则两处权威正是审计 §3.9 关掉的那类缺陷。
 */
object KnowledgeReadiness {
    /**
     * τ_ready：前提视为已具备、知识点视为已开始掌握的分界。`ReviewPlannerV2` 的「同 KC
     * 配额豁免」用的是同一条分界（该豁免的注释本就写着 below READY_TO_LEARN_THRESHOLD），
     * 因此两者共用一个常量而不是各持一个同值常量——同值的两个常量没有机械联系，
     * 改一处不会让另一处变红，正是漂移的温床。
     */
    const val READY_THRESHOLD = 0.6

    /** 该题最弱的前置（gap 的来源）。 */
    data class BlockingPrerequisite(
        val prerequisiteKnowledgeNodeId: String,
        val prerequisiteMasteryScore: Double,
        val gap: Double,
    )

    /**
     * 候选题的**最弱阻塞前置**：把候选题所有 KC 的所有已知前置铺平，取掌握度最低的那个；
     * 都不低于 τ_ready 时返回 null。
     *
     * 与"每题先各取最弱前置、再取最大 gap"等价（`min` 与 `maxOf(gap)` 在该定义下同序），
     * 但额外保留了**是哪一个前置**——补前置的通道需要知道该给哪份材料，而只返回 gap 数值
     * 会让下游为了找回这个 id 再写一遍同样的推导。
     *
     * **没有掌握度证据的前置不算缺失**：未知 ≠ 不会。若把未知当缺失，任何一次新绑定的前置
     * 关系都会立刻把题目判成"前置缺失"，于是通道被噪声淹没而不是被信号驱动。
     *
     * 掌握度相同时按 id 字典序取，保证同一份快照下结果确定——否则会话可能在两个同样弱的
     * 前置之间反复改选，学员每次打开看到的补救材料都不一样。
     */
    fun weakestBlockingPrerequisite(
        knowledgeNodeIds: Set<String>,
        prerequisitesByNode: Map<String, Set<String>>,
        masteryScoreOf: (String) -> Double?,
    ): BlockingPrerequisite? {
        val weakest = knowledgeNodeIds
            .asSequence()
            .flatMap { knowledgeNodeId -> prerequisitesByNode[knowledgeNodeId].orEmpty().asSequence() }
            .distinct()
            .mapNotNull { prerequisiteId ->
                masteryScoreOf(prerequisiteId)?.let { prerequisiteId to it }
            }
            .filter { (_, mastery) -> mastery < READY_THRESHOLD }
            .minWithOrNull(
                compareBy<Pair<String, Double>> { (_, mastery) -> mastery }.thenBy { (id, _) -> id },
            )
            ?: return null
        return BlockingPrerequisite(
            prerequisiteKnowledgeNodeId = weakest.first,
            prerequisiteMasteryScore = weakest.second,
            gap = (READY_THRESHOLD - weakest.second).coerceIn(0.0, 1.0),
        )
    }

    /** [weakestBlockingPrerequisite] 的数值投影，供只需要降权幅度的排程侧使用。 */
    fun gapOf(
        knowledgeNodeIds: Set<String>,
        prerequisitesByNode: Map<String, Set<String>>,
        masteryScoreOf: (String) -> Double?,
    ): Double = weakestBlockingPrerequisite(
        knowledgeNodeIds = knowledgeNodeIds,
        prerequisitesByNode = prerequisitesByNode,
        masteryScoreOf = masteryScoreOf,
    )?.gap ?: 0.0
}
