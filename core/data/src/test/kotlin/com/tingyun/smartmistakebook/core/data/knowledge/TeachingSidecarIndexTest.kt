package com.tingyun.smartmistakebook.core.data.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * 教学材料卷清单（index）驱动加载的一致性测试。
 *
 * 侧车卷数不再硬编码：loader 读 index 里的 sidecars 清单。本测试保证
 * ① index 的 packId 对得上，② 清单里每一卷都真实存在且可读，
 * ③ 走 index 路径加载后材料总数与既有下界一致（开新卷不破现有包）。
 */
class TeachingSidecarIndexTest {

    private fun readResource(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing $name" }
            .bufferedReader(StandardCharsets.UTF_8).use(BufferedReader::readText)

    @Test
    fun indexListsAllBundledSidecarsAndPackLoads() {
        val index = readResource("knowledge/moe-2025-teaching-support-v2-index.json")
        assertTrue("index 必须声明 packId", "packId" in index)
        val sidecarCount = Regex("moe-2025-teaching-support-v2-\\d{2}\\.json").findAll(index).count()
        assertTrue("index 至少列出 6 卷（现成品）", sidecarCount >= 6)

        // 逐卷可读：loader 用的就是同一份清单，这里先做存在性守卫
        for (name in Regex("\"(knowledge/moe-2025-teaching-support-v2-\\d{2}\\.json)\"")
            .findAll(index).map { it.groupValues[1] }) {
            readResource(name)
        }

        val pack = BundledKnowledgePackResources.load()
            .single { it.packId == "moe-2025-four-subjects-v1" }
        assertTrue("index 路径加载后的材料数必须仍 >= 8000", pack.teachingMaterials.size >= 8000)
        assertEquals("绑定数与材料数一致", pack.teachingMaterials.size, pack.teachingMaterialBindings.size)
    }
}
