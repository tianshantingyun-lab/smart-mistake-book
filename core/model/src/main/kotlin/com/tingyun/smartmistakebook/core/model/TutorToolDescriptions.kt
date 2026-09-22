package com.tingyun.smartmistakebook.core.model

/**
 * 工具面文案的**单一来源**（ADR 0001 施工注记）：提示词里的用途描述（长版）与原生
 * function schema 的 description（短版）此前各抄一份、各自演化——MASTERY_UPDATE 的
 * 长版还写着 "terms=[知识点id]（须是当前题真实绑定的知识点）"，与"原始 id 永不进
 * prompt/工具参数"（D5）直接矛盾，审计断链 P2 之一就是它。
 *
 * 现在两处文案都从这里出：改一个工具的契约措辞，只改这一份。
 */
fun TutorToolName.purposeDescription(): String = when (this) {
    TutorToolName.KNOWLEDGE_READ ->
        "读取这道题相关知识点讲解材料（返回**代号**+名称+边界+绑定材料摘要；代号本会话有效，" +
            "可直接作为 MASTERY_UPDATE 的 terms）"
    TutorToolName.NOTEBOOK_READ ->
        "检索错题本中匹配的错题（只查错题库：返回条目本身；掌握情况不在这里，" +
            "要了解某知识点掌握得怎样用 MASTERY_READ）。返回形态随本轮的披露范围：" +
            "本轮没有题锚、披露范围也不含别的题时只回条数与检索词，不列任何条目标题" +
            "（这时不要臆造或复述题目标题）。"
    TutorToolName.MASTERY_READ ->
        "读取学生对相关知识的掌握情况（限当前科目；只查掌握情况，不含错题条目本身——" +
            "要找题用 NOTEBOOK_READ）。terms 留空＝返回本科目全部有学习证据的" +
            "知识点，按最弱优先，每行含名称、粒度、保守掌握度、证据量、状态、最近证据与最近独立" +
            "错误的时间档位、绑定错题数；terms 填知识点关键词＝聚焦解析到的知识点，并额外给出" +
            "结构化历史聚合（独立答对次数及跨几个题目族/学习日、独立错误次数、讲题与测验证据的" +
            "接受与被拒条数）。结果超出字符预算会被截断并注明；确实需要一次拿更多时，把 " +
            "extendedResult 置 true（本地决定实际上限，且每轮只放一次）。"
    TutorToolName.NOTEBOOK_WRITE -> "写入错题本（需学生明确命令，随后由本地确认）"
    TutorToolName.MASTERY_UPDATE ->
        "提交一条学习证据：terms=[**代号**]（本会话已披露知识点的代号 K1..Kn，只能来自知识点" +
            "代号表或 KNOWLEDGE_READ 的返回——绝不编造代号、绝不用原始 id）、" +
            "direction∈{POSITIVE,NEGATIVE}（学生这次是掌握还是卡住）、" +
            "understanding∈{STRUGGLING,UNCERTAIN,CONFIDENT,MASTERED}（你对学生理解程度的判断）、" +
            "confidence∈[0,1]（你判断的置信度，低于 0.7 本地拒写）。" +
            "写不写由你按语义判定：学生对已披露知识点给出理解性陈述或可观察行为时写；" +
            "纯寒暄不写。rationale 必须用引号逐字引用学生原话或可观察行为" +
            "（引文锚底线：POSITIVE≥1 条、MASTERED≥2 条，本地逐条比对，编造引文不算证据）。" +
            "判断必须基于学生在本对话中表现出的可观察行为，不得凭学生口头声称或你的整体印象；" +
            "学生说\"我懂了\"不算掌握证据，只能当作待验证的线索。"
}

/** 原生 function schema 用的短描述（与 [purposeDescription] 同一来源，措辞收紧）。 */
fun TutorToolName.nativePurposeDescription(): String = when (this) {
    TutorToolName.KNOWLEDGE_READ -> "读取当前题相关知识点讲解材料（返回代号+名称+边界+材料摘要）"
    TutorToolName.NOTEBOOK_READ ->
        "检索错题本中匹配的错题（只查错题库，不含掌握情况）"
    TutorToolName.MASTERY_READ ->
        "读取学生对相关知识的掌握情况（terms 留空＝本科目全部清单，填关键词＝聚焦并附历史聚合；不含错题条目）"
    TutorToolName.MASTERY_UPDATE ->
        "提交一条学习证据（terms[0]＝本会话已披露知识点的代号；模型判 direction/understanding/confidence，" +
            "权重与门控本地定）"
    TutorToolName.NOTEBOOK_WRITE -> "写入错题本（需学生明确命令，随后由本地确认）"
}
