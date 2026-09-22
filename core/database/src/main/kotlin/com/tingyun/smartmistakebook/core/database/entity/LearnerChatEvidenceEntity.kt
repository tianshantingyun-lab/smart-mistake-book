package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * 学习证据提交（spec model-intent-routing §5/§9.1）。模型在工具环内调用
 * mastery_update 时提交的证据事件——模型只给"发生了什么"（direction、理由、
 * 引用），weight 由本地常量封顶赋值，掌握度数值由投影器公式产生。
 *
 * 该表是审计与批量撤销的锚点：source_kind = MODEL_CHAT，conversation_id 关联
 * 产生证据的会话，可按会话批量撤销。
 *
 * 索引（按批量录入量级设计，万条/年）：写闸每次评估需要
 * ① learner+KC 的最近写入（同 KC 冷却）② learner 时间窗计数（滚动窗配额）
 * ③ conversation 计数（会话配额）——三个索引分别覆盖，避免全表扫描。
 */
@Entity(
    tableName = "learner_chat_evidence",
    primaryKeys = ["evidence_id"],
    indices = [
        Index(value = ["learner_id", "knowledge_node_id", "created_at_epoch_millis"]),
        Index(value = ["learner_id", "created_at_epoch_millis"]),
        Index(value = ["conversation_id"]),
    ],
)
data class LearnerChatEvidenceEntity(
    val evidence_id: String,
    val learner_id: String,
    val conversation_id: String,
    val knowledge_node_id: String,
    /** POSITIVE（学生自报掌握，低权重档）或 NEGATIVE（卡点，标准自报档）。 */
    val direction: String,
    /** 本地封顶的证据权重（对齐自报档位），投影器积分用。 */
    val weight: Double,
    val reason_markdown: String,
    val confidence: Double,
    /** 恒为 MODEL_CHAT——审计与批量撤销锚点。 */
    val source_kind: String,
    val created_at_epoch_millis: Long,
    /**
     * v43: 门控拒写（research tutor-evidence-gate §3.3：被拒 ≠ 删除）——
     * 非 NULL 表示该证据被本地门控拒绝，只作审计/补救观察，**不进投影**。
     * NULL = 正常证据。
     */
    val rejected_reason: String? = null,
    val rejected_at_epoch_millis: Long? = null,
    /**
     * v50（ADR 0001 / D10）：写入时目标知识点的**锚定等级**，三值由系统机械确立
     * （CONFIRMED 当前题确认绑定 / CANDIDATE 当前题检索候选 / DISCLOSED 其余披露），
     * 不依赖模型声称。**纯数据列（审计用）**：投影积分只读 `weight`——非 CONFIRMED 的降权
     * （×0.5，D9 唯一数值分支）在写入时已施加在 weight 上，本列只留"这条证据是哪一档来路"
     * 供校准。NULL = 无代号通道的行（legacy 行，列引入前写入；或客观作答通道
     * `source_kind=KNOWLEDGE_QUIZ` 的直写行），一律按全权重对待，不追溯降权。
     */
    val anchor_class: String? = null,
) {
    /** True when this row is a rejected (observation-only, non-projected) evidence. */
    val isRejected: Boolean
        get() = rejected_reason != null
}
