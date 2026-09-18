package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialContract
import org.junit.Test

/**
 * 内置包的教学材料必须直接通过数据库导入契约。
 *
 * 它消灭的失败（KD-15）：sidecar 里 material.reviewedAt 早于 source.importedAt 几毫秒，
 * codec 层解码全绿，但真机导入时契约拒绝整批材料、启动横幅报警。此前只有契约夹具测试，
 * 没有"内置包×契约"的端到端校验，这类时间倒挂只能在设备上才暴露。
 */
class BundledTeachingMaterialsContractTest {

    @Test
    fun bundledTeachingMaterialsSatisfyDatabaseContract() {
        val packs = BundledKnowledgePackResources.load()
        var validated = 0
        for (pack in packs) {
            if (pack.teachingMaterials.isEmpty()) continue
            KnowledgeTeachingMaterialContract.validate(
                materials = pack.teachingMaterials,
                bindings = pack.teachingMaterialBindings,
                nodes = pack.nodes,
                sources = pack.teachingSources,
            )
            validated += pack.teachingMaterials.size
        }
        org.junit.Assert.assertTrue("内置包应有教学材料可校验", validated > 0)
    }
}
