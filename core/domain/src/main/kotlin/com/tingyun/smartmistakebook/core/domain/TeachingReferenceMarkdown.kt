package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorTeachingReference

/**
 * 把一份已审校的讲解材料渲染成会话内**只读**呈现的正文：材料正文 + 适用范围。
 *
 * 边界（`boundaryMarkdown`）必须一并呈现：这两条注入通道（重教 [ReTeachOpening]、
 * 前置补救 [PrerequisiteRemediation]）给出的都是"针对**某类**错误认知"的材料，不写出它的
 * 适用条件，就等于让学员把它外推到不成立的题目上——而 `MISCONCEPTION_GUIDE` /
 * `WORKED_EXAMPLE` 恰是最容易被过度外推的两类。该字段由 [TutorTeachingReference] 的构造
 * 约束保证非空，故这里没有空值分支。
 *
 * 为什么单独成一处：两条通道此前会各写一遍同样的拼接。渲染规则一旦分叉，学员在"重教"
 * 与"前置补救"两个入口看到同一份材料却读到不同内容（例如其中一处漏掉适用范围），而这种
 * 分叉不会让任何测试变红。与其复制两份，不如只留一份权威。
 */
internal fun TutorTeachingReference.asReadOnlyTeachingBlock(): String =
    contentMarkdown.trim() + "\n\n**适用范围**：" + boundaryMarkdown.trim()
