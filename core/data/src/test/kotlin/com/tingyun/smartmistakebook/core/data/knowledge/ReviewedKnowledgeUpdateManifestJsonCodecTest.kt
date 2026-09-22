package com.tingyun.smartmistakebook.core.data.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 取代映射台账 codec 的用例。
 *
 * schema 2 的三条承重不变量各有对应用例：
 * 1. version 是**非负整数本体**（字符串/负数/小数都拒）——单调版本号是回滚判据；
 * 2. 一跳到底——MERGE 目标自身不得已退役（双跳重定向把学生数据送到墓碑节点）；
 * 3. schema 1 保持可解码——存量台账里有真实多级链，旧形态必须能读。
 */
class ReviewedKnowledgeUpdateManifestJsonCodecTest {

    private fun entry(
        nodeId: String,
        supersededBy: String?,
        kind: String = "MERGE",
        reason: String = "测试",
    ): String =
        """{"nodeId":"$nodeId","supersededBy":${supersededBy?.let { "\"$it\"" } ?: "null"},"kind":"$kind","reason":"$reason"}"""

    @Test
    fun decodesSchema2Manifest() {
        val raw = """
            {
             "schemaVersion": 2,
             "packId": "moe-2025-four-subjects-v1",
             "version": 7,
             "contentVersion": "deadbeefcafe0123",
             "retired": [${entry("kb:p:m:atomic:a", "kb:p:m:atomic:s")},
                         ${entry("kb:p:m:atomic:b", null, "DELETE")}]
            }
        """.trimIndent()
        val manifest = ReviewedKnowledgeUpdateManifestJsonCodec.decode(raw)
        assertEquals("moe-2025-four-subjects-v1", manifest.packId)
        assertEquals("deadbeefcafe0123", manifest.contentVersion)
        assertEquals(7L, manifest.version)
        assertEquals(2, manifest.retired.size)
        assertEquals(
            "kb:p:m:atomic:s",
            manifest.retirementByNodeId["kb:p:m:atomic:a"],
        )
        assertNull(manifest.retirementByNodeId["kb:p:m:atomic:b"])
    }

    @Test
    fun schema1ManifestStillDecodes() {
        // 存量 schema 1：没有 version 键、允许多级链（解析侧历史行为）。
        // 这条用例钉住"旧 App 升级后仍能读旧台账"的兼容承诺。
        val raw = """
            {
             "schemaVersion": 1,
             "packId": "p",
             "contentVersion": "0011223344556677",
             "retired": [${entry("kb:p:m:atomic:x2", "kb:p:m:atomic:x1")},
                         ${entry("kb:p:m:atomic:x1", "kb:p:m:atomic:x")}]
            }
        """.trimIndent()
        val manifest = ReviewedKnowledgeUpdateManifestJsonCodec.decode(raw)
        assertEquals(0L, manifest.version)
        assertEquals(2, manifest.retired.size)
        // schema 1 不强制一跳：x2 的目标 x1 自己也在 retired 里
        assertEquals("kb:p:m:atomic:x1", manifest.retirementByNodeId["kb:p:m:atomic:x2"])
    }

    @Test
    fun rejectsSchema2ChainJump() {
        // 一跳到底违规：a→b 且 b 自身已退役。
        val raw = """
            {
             "schemaVersion": 2,
             "packId": "p",
             "version": 1,
             "contentVersion": "0011223344556677",
             "retired": [${entry("kb:p:m:atomic:a", "kb:p:m:atomic:b")},
                         ${entry("kb:p:m:atomic:b", "kb:p:m:atomic:c")}]
            }
        """.trimIndent()
        try {
            ReviewedKnowledgeUpdateManifestJsonCodec.decode(raw)
            fail("schema 2 台账含双跳链，必须拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("one hop"))
        }
    }

    @Test
    fun rejectsInvalidVersionValues() {
        fun versionJson(versionLiteral: String): String = """
            {
             "schemaVersion": 2,
             "packId": "p",
             "version": $versionLiteral,
             "contentVersion": "0011223344556677",
             "retired": []
            }
        """.trimIndent()

        // 契约异常：require 抛 IllegalArgumentException、error 抛 IllegalStateException，
        // 两种都算"被拒"——断言类型不钉死具体一种（拒绝行为才是契约）。
        for (literal in listOf("\"1\"", "-1", "1.5", "null")) {
            val thrown = runCatching {
                ReviewedKnowledgeUpdateManifestJsonCodec.decode(versionJson(literal))
            }.exceptionOrNull()
            assertTrue(
                "version=$literal 必须被拒（契约异常），实际：$thrown",
                thrown is IllegalArgumentException || thrown is IllegalStateException,
            )
        }
        // version 键缺失（schema 2 要求恰好五键）
        val missing = runCatching {
            ReviewedKnowledgeUpdateManifestJsonCodec.decode(
                versionJson("1").replace(""" "version": 1,""", ""),
            )
        }.exceptionOrNull()
        assertTrue(
            "schema 2 缺 version 键必须被拒，实际：$missing",
            missing is IllegalArgumentException || missing is IllegalStateException,
        )
        // 零是合法的（新台账首次晋升前）
        assertEquals(0L, ReviewedKnowledgeUpdateManifestJsonCodec.decode(versionJson("0")).version)
    }

    @Test
    fun rejectsUnknownSchemaAndExtraKeys() {
        val base = """
            {
             "schemaVersion": 2,
             "packId": "p",
             "version": 1,
             "contentVersion": "0011223344556677",
             "retired": []
            }
        """.trimIndent()
        val unknownSchema = runCatching {
            ReviewedKnowledgeUpdateManifestJsonCodec.decode(
                base.replaceFirst("\"schemaVersion\": 2", "\"schemaVersion\": 3"),
            )
        }.exceptionOrNull()
        assertTrue(
            "未知 schemaVersion 必须被拒，实际：$unknownSchema",
            unknownSchema is IllegalArgumentException || unknownSchema is IllegalStateException,
        )
        val extraKey = runCatching {
            ReviewedKnowledgeUpdateManifestJsonCodec.decode(
                base.replace("\"retired\": []", "\"retired\": [], \"extra\": 1"),
            )
        }.exceptionOrNull()
        assertTrue(
            "未知根键必须被拒，实际：$extraKey",
            extraKey is IllegalArgumentException || extraKey is IllegalStateException,
        )
    }

    @Test
    fun bundledManifestDecodes() {
        // 随包发布的台账必须可解码：它随 APK 分发，坏台账 = 老安装整包拒更。
        // （`return fail(...)` 让 elvis 右侧是 Nothing——Java void 的 fail 直接当
        // 右操作数只会把左侧推断成 Any。）
        val manifest = BundledKnowledgePackResources.updateManifest
            ?: return fail("随包台账缺失")
        assertEquals("moe-2025-four-subjects-v1", manifest.packId)
        assertTrue(manifest.retired.isNotEmpty())
        // 发布形态必须 schema 2 的产物：一跳到底在解码侧已被强制，
        // 这里再显式断言，让"台账被换回旧形态"这件事在 CI 上可见。
        val retiredIds = manifest.retired.mapTo(mutableSetOf()) { it.nodeId }
        assertEquals(
            emptyList<String>(),
            manifest.retired.filter {
                it.kind == "MERGE" && it.supersededBy != null && it.supersededBy in retiredIds
            }.map { it.nodeId },
        )
    }
}
