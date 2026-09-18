package com.tingyun.smartmistakebook.core.database.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * 内容调和的进度锚点——每个随包内容包一行。
 *
 * **它消灭的失败**：改前安装器是"逐行相等否则整包拒绝"，于是无法回答"上次应用到哪了"，
 * 也没有任何东西能区分"已完整应用"与"装到一半崩了"。结果是第一次调内容更新就把老用户的
 * 知识库弄停摆（横幅常驻、自动分类暂缓）。
 *
 * **崩溃安全靠的是差分的幂等性，不是大事务**：每个对象独立判定 insert/update/tombstone，
 * 重跑收敛。这一行**最后写**，因此 `contentVersion` 与包一致 ⟺ 上一次应用完整跑完；
 * 崩在中途则版本没推进，下次启动重跑同一份差分即可。
 *
 * 不记录逐对象哈希：比对本身（包 vs 表）就是事实来源，多存一份哈希只多一处会漂移的状态。
 */
@Entity(tableName = "content_install_state")
internal data class ContentInstallStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "pack_id")
    val packId: String,
    /** 与随包台账 `contentVersion` 一致时走快速路径（跳过全量比对）。 */
    @ColumnInfo(name = "content_version")
    val contentVersion: String,
    @ColumnInfo(name = "applied_at_epoch_millis")
    val appliedAtEpochMillis: Long,
    /**
     * 上一次应用里**被跳过**的对象数（校验不通过的单条）。
     *
     * 逐对象校验让一个坏对象只影响它自己，不再整包停摆——但"跳过了"必须可见，
     * 否则会退化成"静默少更新"。见 [skippedDetail]。
     */
    @ColumnInfo(name = "skipped_count", defaultValue = "0")
    val skippedCount: Int = 0,
    /** 被跳过对象的定位（换行分隔，有上限），供诊断与重试。 */
    @ColumnInfo(name = "skipped_detail", defaultValue = "''")
    val skippedDetail: String = "",
)
