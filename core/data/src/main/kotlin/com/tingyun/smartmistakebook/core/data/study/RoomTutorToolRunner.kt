package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.KnowledgeSearchFeatureExtractor
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.model.TutorToolCall
import com.tingyun.smartmistakebook.core.model.TutorToolName
import com.tingyun.smartmistakebook.core.model.TutorToolOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Executes locally authorized read tools for the tutor tool loop
 * (spec model-intent-routing §2/§4). Every outcome is a capped markdown
 * digest — the model never sees raw rows, and failures become error
 * outcomes instead of exceptions so the loop can continue.
 */
internal class RoomTutorToolRunner(private val port: StudyDatabasePort) {
    /** 观测面：工具环协议测试断言执行器确实被调用。 */
    var executedCallCount: Int = 0
        private set

    /** Student context the tools need; lobby sessions have no subject. */
    data class Context(
        val subject: String?,
        val learnerId: String = "learner:local",
    )

    suspend fun run(call: TutorToolCall, context: Context): TutorToolOutcome {
        executedCallCount += 1
        return try {
        when (call.tool) {
            TutorToolName.KNOWLEDGE_READ -> {
                val subject = context.subject
                if (subject.isNullOrBlank()) {
                    TutorToolOutcome(
                        tool = call.tool,
                        ok = false,
                        summaryMarkdown = "当前会话没有科目上下文，无法查询知识库。",
                        errorKind = "no_subject",
                    )
                } else {
                    knowledgeRead(subject, call.terms)
                }
            }
            TutorToolName.NOTEBOOK_READ -> notebookRead(call.terms)
            TutorToolName.MASTERY_READ -> masteryRead(context.learnerId)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        TutorToolOutcome(
            tool = call.tool,
            ok = false,
            summaryMarkdown = "查询没有完成，可以换个说法再试。",
            errorKind = "failed",
        )
    }
    }

    private suspend fun knowledgeRead(subject: String, terms: List<String>): TutorToolOutcome {
        val features = KnowledgeSearchFeatureExtractor.fromQuestion(terms.joinToString(" "))
        val nodes = port.readSubjectKnowledgeRecallCandidates(
            subject = subject,
            searchFeatures = features,
            limit = 5,
        )
        if (nodes.isEmpty()) {
            return TutorToolOutcome(
                tool = TutorToolName.KNOWLEDGE_READ,
                ok = true,
                summaryMarkdown = "知识库里没有匹配的知识点。",
            )
        }
        val lines = nodes.mapIndexed { index, node ->
            val boundary = node.boundaryMarkdown?.take(80)
            "${index + 1}. ${node.displayName}${boundary?.let { "：$it" } ?: ""}"
        }
        return TutorToolOutcome(
            tool = TutorToolName.KNOWLEDGE_READ,
            ok = true,
            summaryMarkdown = "知识点候选 ${nodes.size} 个：\n${lines.joinToString("\n")}",
        )
    }

    private suspend fun notebookRead(terms: List<String>): TutorToolOutcome {
        val searchText = terms.joinToString(" ").take(120)
        val rows = port.libraryCatalogPage(
            searchText = searchText,
            subjectId = null,
            sectionId = null,
            knowledgePointId = null,
            masteryId = null,
            sort = "RECENTLY_CREATED",
            offset = 0,
            limit = 6,
        )
        if (rows.isEmpty()) {
            return TutorToolOutcome(
                tool = TutorToolName.NOTEBOOK_READ,
                ok = true,
                summaryMarkdown = "错题本里没有匹配的条目。",
            )
        }
        val lines = rows.mapIndexed { index, row ->
            "${index + 1}. ${row.title}（${row.subject}）"
        }
        return TutorToolOutcome(
            tool = TutorToolName.NOTEBOOK_READ,
            ok = true,
            summaryMarkdown = "错题本匹配 ${rows.size} 条：\n${lines.joinToString("\n")}",
        )
    }

    private suspend fun masteryRead(learnerId: String): TutorToolOutcome {
        val lattice = port.observeKnowledgeQuestionLattice(learnerId).first().take(24)
        if (lattice.isEmpty()) {
            return TutorToolOutcome(
                tool = TutorToolName.MASTERY_READ,
                ok = true,
                summaryMarkdown = "还没有足够的学习记录来评估掌握情况。",
            )
        }
        val mastered = lattice.count { it.kcStatus == "MASTERED" }
        val learning = lattice.count { it.kcStatus == "LEARNING" }
        val conflicted = lattice.count { it.kcStatus == "CONFLICTED" }
        val weakest = lattice
            .filter { it.kcConservativeMastery != null }
            .sortedBy { it.kcConservativeMastery ?: 1.0 }
            .take(5)
            .mapIndexed { index, row ->
                "${index + 1}. 掌握度 ${"%.2f".format(row.kcConservativeMastery)}：${row.practiceUnitId}"
            }
        return TutorToolOutcome(
            tool = TutorToolName.MASTERY_READ,
            ok = true,
            summaryMarkdown = buildString {
                append("学习记录覆盖 ${lattice.size} 条，其中已掌握 $mastered、学习中 $learning、冲突 $conflicted。")
                if (weakest.isNotEmpty()) {
                    append("\n最薄弱：\n${weakest.joinToString("\n")}")
                }
            },
        )
    }
}
