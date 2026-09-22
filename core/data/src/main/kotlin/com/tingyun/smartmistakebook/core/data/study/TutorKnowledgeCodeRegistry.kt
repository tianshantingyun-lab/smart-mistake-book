package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.model.TutorKnowledgeCode
import com.tingyun.smartmistakebook.core.model.TutorKnowledgeCode.Companion.codeNumber

/**
 * 会话级知识点代号注册表（ADR 0001 / D5 单一代号通道的服务端半边）。
 *
 * 进程内、session 作用域（由 `RoomModelTaskRepository` 按 sessionId 持有）：**代码首次
 * 披露时分配 K1..Kn，会话内稳定**——同一节点整个会话只拿得到同一个代号，模型在轮 1
 * 学到的 K3 到轮 5 还是同一个 K3。
 *
 * 为什么是内存而不是落库：代号是**披露顺序**的函数，而披露顺序跨进程不可重建
 * （KNOWLEDGE_READ 的追加披露发生在运行中）；持久化的输入行里已经带着派生前仓库
 * 写回的赋码形状（`TutorKnowledgeCode.code`），[adopt] 对已赋码条目只做一致性登记，
 * 所以进程重启后基础代号仍与存库行一致，丢的只有"崩溃前工具发现"的追加代号——
 * 它们本来也只在该进程的后续轮次里可见。
 *
 * 线程安全：工具环在 `Dispatchers.IO` 的协程里按轮串行使用本注册表；[ConcurrentHashMap]
 * 之外不引入锁，因为"赋码"只发生在一个 repository 实例的 execute() 单线程推进里
 * （同一 sessionId 的轮次由 processActiveOperations 的排重串行化）。
 */
internal class TutorKnowledgeCodeRegistry {
    private val nodeToCode = LinkedHashMap<String, String>()
    private val codeToEntry = LinkedHashMap<String, TutorKnowledgeCode>()
    private var nextIndex = 1

    /**
     * 把一份**派发前**的代号条目登记进会话：已赋码条目按原码登记（必须与已有登记一致，
     * 否则说明两份持久化形状在同一个会话里漂移——直接 fail，不静默重编号），未赋码条目
     * 按列表顺序首次分配。返回条目在 [entries] 原顺序下的赋码视图。
     */
    fun adopt(entries: List<TutorKnowledgeCode>): List<TutorKnowledgeCode> {
        val adopted = LinkedHashMap<String, TutorKnowledgeCode>(entries.size)
        entries.forEach { entry ->
            val code = if (entry.code != null) {
                adoptExisting(entry)
            } else {
                assign(entry)
            }
            adopted[entry.knowledgeNodeId] = entry.copy(code = code)
        }
        return adopted.values.toList()
    }

    /** 读工具发现的新节点：分配（或返回既有）代号，追加披露。 */
    fun assign(entry: TutorKnowledgeCode): String {
        require(entry.code == null) { "assign() takes uncoded entries, got ${entry.code}" }
        nodeToCode[entry.knowledgeNodeId]?.let { return it }
        require(nextIndex <= MAX_CODES_PER_SESSION) {
            "Session knowledge-code space exhausted ($MAX_CODES_PER_SESSION)"
        }
        val code = "K$nextIndex"
        nextIndex += 1
        nodeToCode[entry.knowledgeNodeId] = code
        codeToEntry[code] = entry.copy(code = code)
        return code
    }

    /** 服务端解析：代号 → 条目（含原始 id 与角色）。未披露/编造 → null（结构性拒）。 */
    fun resolve(code: String): TutorKnowledgeCode? =
        runCatching { codeNumber(code) }.getOrNull()?.let { codeToEntry[code] }

    fun codeFor(knowledgeNodeId: String): String? = nodeToCode[knowledgeNodeId]

    /** 本会话已披露代号集合——MASTERY_UPDATE 的 enum 白名单（服务端半边）。 */
    fun disclosedCodes(): Set<String> = codeToEntry.keys

    /** 全部已披露条目，按代号升序（= 首次披露顺序）。 */
    fun disclosed(): List<TutorKnowledgeCode> =
        codeToEntry.values.sortedBy { entry -> entry.code?.let(::codeNumber) ?: 0 }

    private fun adoptExisting(entry: TutorKnowledgeCode): String {
        val code = requireNotNull(entry.code)
        val existing = nodeToCode[entry.knowledgeNodeId]
        if (existing != null) {
            require(existing == code) {
                "Knowledge node ${entry.knowledgeNodeId} is registered as $existing but the " +
                    "request carries $code — the session code space drifted"
            }
            return code
        }
        codeToEntry[code]?.let { registered ->
            require(registered.knowledgeNodeId == entry.knowledgeNodeId) {
                "Code $code is already registered for ${registered.knowledgeNodeId}"
            }
            return code
        }
        require(codeNumber(code) < MAX_CODES_PER_SESSION) {
            "Adopted code $code exceeds the session code space"
        }
        nodeToCode[entry.knowledgeNodeId] = code
        codeToEntry[code] = entry
        if (codeNumber(code) + 1 > nextIndex) nextIndex = codeNumber(code) + 1
        return code
    }

    private companion object {
        const val MAX_CODES_PER_SESSION = 999
    }
}
