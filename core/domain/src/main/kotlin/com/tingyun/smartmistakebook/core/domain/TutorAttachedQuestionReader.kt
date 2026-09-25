package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.AttachedRoundQuestion

/**
 * 加号菜单「从错题库选择」选中后的读取器：把学生选中的目录条目读成
 * [AttachedRoundQuestion]（题面 + 修订号 + 科目）。
 *
 * 与 [TutorRoundQuestionRetriever] 的分工：检索器把"学生这一轮说的话"变成候选菜单
 * （菜单，不构成绑定）；读取器把"学生这一轮显式选的那道题"读出来（身份，直接成为本轮
 * 题锚）。两条路都在绑定生效前完成读盘，所以派发路径不引入新的可见等待。
 *
 * 返回 null 表示这道题现在读不出可用题面（详情不是 Ready、题面为空）：调用方如实提示
 * "读不到"，而不是把一条没有题面的"添加"带进请求。
 */
fun interface TutorAttachedQuestionReader {
    suspend fun read(entry: StudyCatalogEntry): AttachedRoundQuestion?
}
