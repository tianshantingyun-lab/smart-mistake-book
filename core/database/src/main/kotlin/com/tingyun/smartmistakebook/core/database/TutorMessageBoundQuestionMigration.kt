package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v48→49：`tutor_message` 增加本轮绑定的题引用（学生消息行）。
 *
 * 为什么是两列而不是 link 表：**一轮最多绑一道题**（`TutorRoundQuestionBindingPolicy.resolve`
 * 最多返回一条声明）。link 表表达的是多对多，而这里不存在多对多——一个只能放 0 或 1 行的
 * link 表既没有表达力优势，又要额外承担排序、去重与孤儿清理，还会让"这轮到底有没有题"变成
 * 一次 JOIN 才能回答。两列（`bound_problem_id` + `bound_problem_revision_id`）把"有没有题"和
 * "是哪道题"放在同一行上，读历史时无需联结。
 *
 * 为什么在**学生消息行**上而不是助手回复行上：一轮的锚是学生这一轮说的话——绑定是"学生这一轮
 * 在说哪道题"，而助手回复可能失败、可能重试、可能被取消。挂在学生行上，一轮的绑定不随回复的
 * 重试而变（重试请求逐字相同，落库走幂等分支）。
 *
 * 旧行保持可读：两列可空，旧行读回是 NULL = 无题轮。旧行**不是**被回填成"会话锚点那道题"——
 * 那会把"当年不知道有没有题"写成"当年确定是这道题"，是伪造历史。
 */
internal val TUTOR_MESSAGE_BOUND_QUESTION_MIGRATION_48_49 = object : Migration(48, 49) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `tutor_message` ADD COLUMN `bound_problem_id` TEXT")
        connection.execSQL(
            "ALTER TABLE `tutor_message` ADD COLUMN `bound_problem_revision_id` TEXT",
        )
    }
}
