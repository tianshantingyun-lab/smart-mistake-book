package com.tingyun.smartmistakebook.core.domain

/**
 * 知识点复习范围（spec dual-review-entry §3.2）：从今天复习队列的题（各带绑定
 * knowledgeNodeIds）提取其覆盖的知识点集合——union、去重。纯函数，不依赖 DB。
 */
data class ReviewScopeQuestion(
    val practiceUnitId: String,
    val knowledgeNodeIds: Set<String>,
)

/** 从今天复习队列提取覆盖的知识点集合（union、去重）。 */
fun extractReviewKnowledgeScope(queue: List<ReviewScopeQuestion>): Set<String> =
    queue.flatMapTo(mutableSetOf()) { it.knowledgeNodeIds }
