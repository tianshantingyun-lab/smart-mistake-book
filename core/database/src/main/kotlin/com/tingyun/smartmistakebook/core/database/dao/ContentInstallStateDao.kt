package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert
import com.tingyun.smartmistakebook.core.database.entity.ContentInstallStateEntity

/**
 * 内容调和的进度锚点。
 *
 * 两个用途：
 * 1. **快速路径**：`content_version` 与随包台账一致 ⟺ 上一次调和跑完了，于是跳过
 *    全量比对（否则每次启动都要读 ~2600 节点 + ~11000 材料来确认"什么都没变"）。
 * 2. **诊断**：`skipped_*` 留下上一次被逐条校验跳过的对象——逐对象跳过修掉了"一条坏数据
 *    挡住整包"，但不记下来就会退化成"静默少更新"。
 */
@Dao
internal interface ContentInstallStateDao {
    @Query("SELECT * FROM content_install_state WHERE pack_id = :packId")
    suspend fun read(packId: String): ContentInstallStateEntity?

    @Upsert
    suspend fun upsert(state: ContentInstallStateEntity)
}
