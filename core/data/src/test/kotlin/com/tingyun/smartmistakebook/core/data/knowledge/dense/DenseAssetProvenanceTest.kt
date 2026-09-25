package com.tingyun.smartmistakebook.core.data.knowledge.dense

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 稠密资产的**身份三处一致**门（JVM 侧：只读文件 + 解析，不需要设备、不需要 LiteRT）。
 *
 * 三处 = 常量 [DenseRecallAssembly.VECTOR_ASSET_SHA256]、旁车 `<assetPath>.json`、实际文件
 * `<assetPath>` 的字节实测 sha256（外加旁车 `vectorCount`/`dim` 与资产头的对账）。
 *
 * **它消灭的失败**：`DenseRecallAssembly.kt:87` 的注释声称本测试钉住"这里的常量 == 旁车 ==
 * 实际文件"，但全仓此前**不存在这个测试**（grep `DenseAssetProvenanceTest` 只命中那句注释）——
 * 于是常量过期、旁车过期、`.vec` 被换过这三种情况都没有门：它们在设备上只表现为
 * `DenseVectorAsset.read` 抛错 ⇒ 稠密腿按设计静默回退纯词面，检索质量静默掉档而**没有任何
 * 东西报红**（2026-09-25 Stage-4 就是踩在这个洞上）。本门把这三处一致性变成可离线复算的断言。
 *
 * 资源路径**取自常量本身**（[DenseRecallAssembly.VECTOR_ASSET_PATH] 与 `<它>.json`）：常量
 * 换路径时本测试跟着换，不会出现"门测的是另一份文件"的假绿。
 *
 * **覆盖边界**（明确不在本测试内，避免把门当成了它没测的东西）：
 * - 不覆盖"装配点是否真的把常量传进 `read`"——那条路径在 [DenseRecallAssembly] 的私有运行时里，
 *   JVM 侧没有可用 seam（`DenseRuntime.loadVectorAsset` 拿不到实例），本测试只保证"常量本身
 *   就是这份文件的身份"；
 * - 不覆盖模型件（`dense/bge-small-zh-v1.5-int8.tflite`）与词表的存在性/哈希：本测试只管 `.vec` 这三处；
 * - 不覆盖设备上编码器的可用性（那是 `GoldenRetrievalInstrumentedTest` 的"腿是活的"探针）。
 */
class DenseAssetProvenanceTest {

    private val assetPath = DenseRecallAssembly.VECTOR_ASSET_PATH
    private val sidecarPath = assetPath + ".json"

    private val assetBytes: ByteArray by lazy { DenseTestFixtures.bytes(assetPath) }
    private val sidecar: JsonObject by lazy {
        Json.parseToJsonElement(DenseTestFixtures.text(sidecarPath)).jsonObject
    }

    @Test
    fun `asset sha256 constant equals the sidecar and the real file`() {
        val sidecarSha = sidecar.stringField("sha256")
        val fileSha = DenseVectorAsset.sha256(assetBytes)
        assertEquals(
            "DenseRecallAssembly.VECTOR_ASSET_SHA256 与旁车 " + sidecarPath + " 记录的 sha256 不一致" +
                "（常量过期或旁车过期：两边必须同改同生）",
            sidecarSha, DenseRecallAssembly.VECTOR_ASSET_SHA256,
        )
        assertEquals(
            "实际资产 " + assetPath + " 的实测 sha256 与旁车记录不一致（文件被换过而旁车没重生成）",
            sidecarSha, fileSha,
        )
        // 第三条不是前两条的重复：常量与文件直接对账，红时一眼看出是哪一处对不上（常量/旁车/文件）。
        assertEquals(
            "DenseRecallAssembly.VECTOR_ASSET_SHA256 与实际资产 " + assetPath + " 的实测 sha256 不一致",
            DenseRecallAssembly.VECTOR_ASSET_SHA256, fileSha,
        )
    }

    @Test
    fun `sidecar vector count and dim match the real asset header`() {
        val format = sidecar.objectField("format")
        val corpus = sidecar.objectField("corpus")
        // 用生产常量做身份校验读一遍：常量错 ⇒ 这里直接判资产不可用（与设备上同一条路径）。
        val asset = DenseVectorAsset.read(assetBytes, expectedSha256 = DenseRecallAssembly.VECTOR_ASSET_SHA256)
        val vectorCount = corpus.intField("vectorCount")
        assertEquals("旁车 format.dim 与实际资产头不一致", format.intField("dim"), asset.dim)
        assertEquals("旁车 format.count 与实际资产头不一致", format.intField("count"), asset.count)
        assertEquals("旁车 corpus.vectorCount 与 format.count 不一致", format.intField("count"), vectorCount)
        assertEquals("旁车 corpus.vectorCount 与实际资产行数不一致", vectorCount, asset.count)
        assertEquals("旁车 corpus.atomicNodes 与实际资产的节点数不一致", corpus.intField("atomicNodes"), asset.nodeCount)
        // 旁车自身的账（半新半旧的旁车 = 只有一部分字段被重生成，sha 一致但计数已过期）。
        assertEquals(
            "旁车不自洽：canonicalVectors + aliasVectors != vectorCount",
            vectorCount, corpus.intField("canonicalVectors") + corpus.intField("aliasVectors"),
        )
    }

    private fun JsonObject.objectField(name: String): JsonObject =
        this[name]?.jsonObject ?: error("旁车 $sidecarPath 缺对象字段 $name")

    private fun JsonObject.stringField(name: String): String =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: error("旁车 $sidecarPath 缺字符串字段 $name")

    private fun JsonObject.intField(name: String): Int =
        (this[name] as? JsonPrimitive)?.int ?: error("旁车 $sidecarPath 缺整数字段 $name")
}
