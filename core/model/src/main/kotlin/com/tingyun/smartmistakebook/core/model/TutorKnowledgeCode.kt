package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * 单一代号通道（ADR 0001 / 台账 D5）：知识点在模型侧**只有**代号 `K1..Kn`。
 *
 * 原始 id 永不进 prompt、永不进工具参数——模型没有产生原始 id 的渠道，"编造 id"
 * 这一失败类被结构性消灭。读侧（教学参考、代号映射表、KNOWLEDGE_READ 输出）以代号
 * 披露，写侧 MASTERY_UPDATE 的 `terms[0]` 收代号，服务端（core:data）把代号解析回
 * 原始 id 后才进门。
 *
 * 代号由 core:data 的会话级注册表（进程内、session 作用域）在**首次披露**时按顺序
 * 分配，会话内稳定：同一节点整个会话只拿得到同一个代号。
 */
@Serializable
enum class TutorKnowledgeCodeRole {
    /** 当前题已确认绑定的知识点（绑定在表内，代码侧确立）。 */
    CONFIRMED_BINDING,

    /** 当前题本地检索候选（两段式 B→A 召回，未确认绑定）。 */
    RETRIEVAL_CANDIDATE,

    /** 已披露节点的前置知识点。 */
    PREREQUISITE,

    /** 读工具（KNOWLEDGE_READ）在执行中发现、追加披露的节点。 */
    TOOL_DISCOVERED,
}

/**
 * 写入证据的锚定等级（D9/D10）：`learner_chat_evidence.anchor_class` 落库值。
 *
 * 全部由系统自己机械确立（不依赖模型声称）——取目标节点代号在注册表里的角色：
 * 确认绑定 → [CONFIRMED]；检索候选 → [CANDIDATE]；其余披露（前置/工具发现）→ [DISCLOSED]。
 * 旧行该列为 NULL（legacy），投影时按全权重对待（向后兼容，不得追溯降权）。
 */
@Serializable
enum class KnowledgeAnchorClass {
    CONFIRMED,
    CANDIDATE,
    DISCLOSED;

    companion object {
        fun of(role: TutorKnowledgeCodeRole): KnowledgeAnchorClass = when (role) {
            TutorKnowledgeCodeRole.CONFIRMED_BINDING -> CONFIRMED
            TutorKnowledgeCodeRole.RETRIEVAL_CANDIDATE -> CANDIDATE
            TutorKnowledgeCodeRole.PREREQUISITE,
            TutorKnowledgeCodeRole.TOOL_DISCOVERED,
            -> DISCLOSED
        }
    }
}

/**
 * 一次已披露（或待披露）的知识点代号条目，随 [TutorPlanInput] / [TutorRespondInput]
 * 持久化。
 *
 * [code] 为 null 表示"派发前尚未分配代号"——core:data 的会话注册表在 execute() 入口
 * 统一赋码（首现顺序 K1..Kn）。用 `@EncodeDefault(NEVER)` 保证 null 不落键：旧行
 * （没有 knowledgeCodes 键）与"未赋码的新行"编码形状一致，指纹抹平空载体的纪律
 * （bf8be888 教训）在这一层就成立。
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TutorKnowledgeCode(
    val knowledgeNodeId: String,
    val displayName: String,
    val role: TutorKnowledgeCodeRole,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val code: String? = null,
) {
    init {
        knowledgeNodeId.requireSafeModelText(
            "Knowledge code node id",
            ModelTaskRequest.MAX_ID_CHARS,
            false,
        )
        displayName.requireSafeModelText("Knowledge code display name", MAX_DISPLAY_NAME_CHARS, false)
        code?.let { requireCodeShape(it) }
    }

    companion object {
        const val MAX_DISPLAY_NAME_CHARS = 96
        private val CODE_SHAPE = Regex("K[1-9][0-9]{0,3}")

        fun requireCodeShape(code: String): String {
            require(CODE_SHAPE.matches(code)) {
                "Knowledge code must look like K1..K9999, was $code"
            }
            return code
        }

        fun codeNumber(code: String): Int =
            requireCodeShape(code).removePrefix("K").toInt()
    }
}

/**
 * 代号映射表在提示词里的角色措辞——模型靠它理解每个代号"是什么来路的知识点"。
 * 措辞进 prompt 指纹（policy 版本），改动需 bump [ModelPromptPolicyVersions]。
 */
fun TutorKnowledgeCodeRole.promptRoleLabel(): String = when (this) {
    TutorKnowledgeCodeRole.CONFIRMED_BINDING -> "当前题确认绑定"
    TutorKnowledgeCodeRole.RETRIEVAL_CANDIDATE -> "当前题检索候选"
    TutorKnowledgeCodeRole.PREREQUISITE -> "前置"
    TutorKnowledgeCodeRole.TOOL_DISCOVERED -> "工具发现"
}

/**
 * 一份输入里代号集合的规模上界：映射表要整表进 prompt（稳定前缀区），无界披露
 * 会挤占真正的讲解预算。派发方（feature）与注册表（core:data）共用这条上界。
 */
const val MAX_SESSION_KNOWLEDGE_CODES = 24
