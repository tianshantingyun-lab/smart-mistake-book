package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v46→47：讲题助手消息保存模型给出的思考轨迹。学生可折叠查看“老师是怎么想的”，
 * 思考从不回喂模型；旧行保持 NULL，失败/中断的回复也不落。
 */
internal val TUTOR_MESSAGE_THINKING_MIGRATION_46_47 = object : Migration(46, 47) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `tutor_message` ADD COLUMN `thinking_markdown` TEXT")
    }
}
