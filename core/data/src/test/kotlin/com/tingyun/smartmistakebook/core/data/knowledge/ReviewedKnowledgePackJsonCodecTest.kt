package com.tingyun.smartmistakebook.core.data.knowledge

import com.tingyun.smartmistakebook.core.model.KnowledgeMaterialDerivationKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeSourceContentUsePolicy
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.SubjectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewedKnowledgePackJsonCodecTest {
    @Test
    fun bundledResourceDecodesToReviewedNineSubjectPack() {
        val pack = BundledKnowledgePackResources.load().single {
            it.packId == "moe-2020-foundation-v1"
        }

        assertEquals("moe-2020-foundation-v1", pack.packId)
        assertEquals(
            KnowledgeCoverageContract.HISTORICAL_2020_BASELINE_ID,
            pack.coverage.baselineId,
        )
        assertEquals(KnowledgeCoverageLevel.HISTORICAL_SAMPLE, pack.coverage.catalogLevel)
        assertEquals(SubjectKind.entries.size - 1, pack.sources.size)
        assertEquals(28, pack.nodes.size)
        assertEquals(28, pack.bindings.size)
        assertEquals(2, pack.relations.size)
        assertEquals(2, pack.teachingMaterials.size)
        assertEquals(4, pack.teachingMaterialBindings.size)
        assertEquals(
            setOf(
                KnowledgeTeachingMaterialType.METHOD_MODEL.name,
                KnowledgeTeachingMaterialType.WORKED_EXAMPLE.name,
            ),
            pack.teachingMaterials.mapTo(hashSetOf()) { it.materialType },
        )
        assertEquals(
            "source:moe-2020:math",
            pack.sources.single { it.subject == SubjectKind.MATH.name }.sourceId,
        )
        assertEquals(
            (SubjectKind.entries - SubjectKind.GENERAL).map(SubjectKind::name).toSet(),
            pack.sources.mapTo(hashSetOf()) { it.subject },
        )
    }

    @Test
    fun bundledResourceIsParsedOnceAndReusedAcrossRepositoryCalls() {
        assertSame(
            BundledKnowledgePackResources.load(),
            BundledKnowledgePackResources.load(),
        )
    }

    @Test
    fun fourSubjectPackDecodesWithLargeTeachingSupport() {
        val pack = BundledKnowledgePackResources.load().single {
            it.packId == "moe-2025-four-subjects-v1"
        }
        assertEquals("moe-2025-four-subjects-v1", pack.packId)
        assertEquals(KnowledgeCoverageContract.CURRENT_BASELINE_ID, pack.coverage.baselineId)
        assertEquals(KnowledgeCoverageLevel.PARTIAL, pack.coverage.catalogLevel)
        assertEquals(setOf("MATH","PHYSICS","CHEMISTRY","BIOLOGY"), pack.nodes.mapTo(hashSetOf()) { it.subject })
        // 节点 = topic + 原子点。拓扑会随"章层点下移到主题层"结构修复而增长
        // （每次新建主题 topic，原子点 2573 不变）。2026-09-16 有机化学基础 62 点下移
        // 新增 10 个主题：445→455 topic，3018→3028 节点。
        assertEquals(3028, pack.nodes.size)
        // 多层知识树：卷 -> 章 -> 主题 -> 子主题 -> 知识点，topic 父链必须完整落到节点层
        val topicNodes = pack.nodes.filter { it.nodeKind == "TOPIC" }
        assertTrue(topicNodes.size >= 400)
        assertTrue(topicNodes.count { it.parentKnowledgeNodeId != null } >= 300)
        // 无绑定残渣在聚合层剔除后，材料量 = 绑定量（每个材料都绑到原子节点）
        assertEquals(pack.teachingMaterialBindings.size, pack.teachingMaterials.size)
        assertTrue(pack.teachingMaterials.size >= 8000)
        // 至少 80% 教学条目绑定到目录节点（语义绑定阈值）
        val bound = pack.teachingMaterialBindings.size
        assertTrue(bound >= 8000)
    }

    @Test
    fun historicalSampleCannotBeReportedAsCurrentFullCoverage() {
        val audit = KnowledgeCoverageContract.audit(
            BundledKnowledgePackResources.load().filter { it.packId == "moe-2020-foundation-v1" },
        )

        assertTrue(audit.currentBaselinePackIds.isEmpty())
        assertTrue(audit.currentSubjects.isEmpty())
        assertEquals(0, audit.currentFineGrainedPointCount)
        assertEquals(0, audit.currentTeachingMaterialCount)
        assertFalse(audit.catalogDeclaredFull)
        assertFalse(audit.teachingSupportDeclaredFull)
        assertFalse(audit.fullCoverageReady)
    }

    @Test
    fun currentSchemaSupportsMultipleTopicsAndCrossTopicPrerequisites() {
        val pack = ReviewedKnowledgePackJsonCodec.decode(currentTwoTopicPack())

        assertEquals(KnowledgeCoverageContract.CURRENT_BASELINE_ID, pack.coverage.baselineId)
        assertEquals(KnowledgeCoverageLevel.PARTIAL, pack.coverage.catalogLevel)
        assertEquals(4, pack.nodes.size)
        assertEquals(1, pack.relations.size)
        assertEquals(
            setOf("函数概念", "函数性质"),
            pack.nodes
                .filter { it.parentKnowledgeNodeId == null }
                .mapTo(linkedSetOf()) { it.displayName },
        )
        assertTrue(pack.sources.single().edition.orEmpty().contains("2025年修订"))
    }

    @Test
    fun currentPackCannotFalselyDeclareFullCoverageForOneSubject() {
        val failure = runCatching {
            ReviewedKnowledgePackJsonCodec.decode(
                currentTwoTopicPack()
                    .replace("\"catalogLevel\":\"PARTIAL\"", "\"catalogLevel\":\"FULL\""),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("all nine subjects"))
    }

    @Test
    fun rejectsUnknownFieldsInsteadOfSilentlyAcceptingSchemaDrift() {
        val failure = runCatching {
            ReviewedKnowledgePackJsonCodec.decode(validSingleSubjectJson(extraRoot = ""","typo":true"""))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("unknown keys"))
    }

    @Test
    fun rejectsDuplicateSubjects() {
        val subject = singleSubjectBlock()
        val failure = runCatching {
            ReviewedKnowledgePackJsonCodec.decode(
                validRoot(subjects = "$subject,$subject"),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("subjects must be unique"))
    }

    @Test
    fun rejectsPrerequisiteOutsideTheReviewedSubjectBlock() {
        val failure = runCatching {
            ReviewedKnowledgePackJsonCodec.decode(
                validSingleSubjectJson(prerequisiteSlugs = """["missing-point"]"""),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("same subject block"))
    }

    @Test
    fun teachingSidecarRejectsQuestionBankShapedSchemaDrift() {
        val pack = ReviewedKnowledgePackJsonCodec.decode(
            requireNotNull(
                requireNotNull(ReviewedKnowledgePackJsonCodecTest::class.java.classLoader)
                    .getResourceAsStream("knowledge/moe-2020-foundation-v1.json"),
            )
                .bufferedReader()
                .use { it.readText() },
        )
        val raw = requireNotNull(
            requireNotNull(ReviewedKnowledgePackJsonCodecTest::class.java.classLoader)
                .getResourceAsStream("knowledge/moe-2020-teaching-support-v1.json"),
        )
            .bufferedReader()
            .use { it.readText() }
            .replace(
                "\"materials\": [",
                "\"questionBank\": [],\n  \"materials\": [",
            )

        val failure = runCatching {
            ReviewedTeachingMaterialSidecarJsonCodec.decode(raw, pack)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("unknown keys"))
    }

    @Test
    fun workedExampleMayContainACompleteSolutionButCannotGainAssessmentAuthority() {
        val pack = ReviewedKnowledgePackJsonCodec.decode(
            classpathResource("knowledge/moe-2020-foundation-v1.json"),
        )
        val original = classpathResource("knowledge/moe-2020-teaching-support-v1.json")
        val decoded = ReviewedTeachingMaterialSidecarJsonCodec.decode(original, pack)
        val workedExample = decoded.materials.single {
            it.materialType == KnowledgeTeachingMaterialType.WORKED_EXAMPLE.name
        }

        assertTrue(workedExample.contentMarkdown.contains("函数在"))
        assertTrue(workedExample.contentMarkdown.contains("递增"))
        listOf(
            """"answerKey":"A",""",
            """"score":10,""",
            """"difficulty":"EASY",""",
            """"reviewSchedule":"DAILY",""",
        ).forEach { forbiddenField ->
            val altered = original.replace(
                "\"bindings\": [",
                "$forbiddenField\n      \"bindings\": [",
                ignoreCase = false,
            )
            val failure = runCatching {
                ReviewedTeachingMaterialSidecarJsonCodec.decode(altered, pack)
            }.exceptionOrNull()
            assertTrue(
                "Expected assessment field to be rejected: $forbiddenField",
                failure is IllegalArgumentException &&
                    failure.message.orEmpty().contains("unknown keys"),
            )
        }
    }

    @Test
    fun teachingSidecarDistinguishesCompleteSolutionsAndDerivationsFromQuestionBanks() {
        val pack = ReviewedKnowledgePackJsonCodec.decode(
            classpathResource("knowledge/moe-2020-foundation-v1.json"),
        )

        listOf(
            KnowledgeTeachingMaterialType.COMPLETE_SOLUTION,
            KnowledgeTeachingMaterialType.DERIVATION,
        ).forEach { type ->
            val decoded = ReviewedTeachingMaterialSidecarJsonCodec.decode(
                licensedAdaptationSidecar().replace(
                    "\"type\":\"WORKED_EXAMPLE\"",
                    "\"type\":\"${type.name}\"",
                ),
                pack,
            )

            assertEquals(type.name, decoded.materials.single().materialType)
        }
    }

    @Test
    fun currentTeachingSidecarCarriesSpecificReuseTermsForLicensedAdaptation() {
        val pack = ReviewedKnowledgePackJsonCodec.decode(
            classpathResource("knowledge/moe-2020-foundation-v1.json"),
        )
        val sidecar = ReviewedTeachingMaterialSidecarJsonCodec.decode(
            licensedAdaptationSidecar(),
            pack,
        )

        assertEquals(
            KnowledgeSourceContentUsePolicy.ADAPTATION_ALLOWED.name,
            sidecar.sources.single().contentUsePolicy,
        )
        assertEquals(
            "CC-BY-NC-SA-4.0",
            sidecar.sources.single().licenseExpression,
        )
        assertEquals(
            KnowledgeMaterialDerivationKind.LICENSED_ADAPTATION.name,
            sidecar.materials.single().derivationKind,
        )
        assertTrue(sidecar.materials.single().contentMarkdown.contains("完整解答"))
    }

    private fun validSingleSubjectJson(
        extraRoot: String = "",
        prerequisiteSlugs: String = "[]",
    ): String = validRoot(singleSubjectBlock(prerequisiteSlugs), extraRoot)

    private fun validRoot(
        subjects: String,
        extraRoot: String = "",
    ): String =
        """
        {
          "schemaVersion":1,
          "packId":"test-foundation-v1",
          "taxonomyVersion":"test-foundation-v1",
          "sourceNamespace":"test",
          "reviewedAtEpochMillis":1,
          "sourceUri":"https://example.org/curriculum.pdf",
          "subjects":[$subjects]
          $extraRoot
        }
        """.trimIndent()

    private fun singleSubjectBlock(prerequisiteSlugs: String = "[]"): String =
        """
        {
          "subject":"MATH",
          "sourceFingerprint":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
          "topicSlug":"function",
          "topicName":"函数",
          "topicLocator":"第1页",
          "knowledgePoints":[{
            "slug":"read-graph",
            "name":"从图象读取信息",
            "aliases":[],
            "kind":"REPRESENTATION",
            "boundary":"只读取图中已有信息。",
            "sourceLocator":"第1页",
            "prerequisiteSlugs":$prerequisiteSlugs
          }]
        }
        """.trimIndent()

    private fun currentTwoTopicPack(): String =
        """
        {
          "schemaVersion":2,
          "packId":"moe-2025-math-reviewed-v1",
          "taxonomyVersion":"moe-2025-math-reviewed-v1",
          "sourceNamespace":"moe-2025-reviewed-v1",
          "reviewedAtEpochMillis":2,
          "sourceUri":"https://www.ictr.edu.cn/policy/cheng/p/11.html",
          "coverage":{
            "baselineId":"moe-high-school-2017-2025",
            "catalogLevel":"PARTIAL",
            "teachingSupportLevel":"PARTIAL"
          },
          "subjects":[{
            "subject":"MATH",
            "sourceFingerprint":"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
            "topics":[
              {
                "slug":"function-concept",
                "name":"函数概念",
                "sourceLocator":"函数主题",
                "knowledgePoints":[{
                  "slug":"recognize-function-relation",
                  "name":"判断两个变量是否构成函数关系",
                  "aliases":[],
                  "kind":"CONCEPT",
                  "boundary":"只判断给定对应关系是否满足函数定义。",
                  "sourceLocator":"函数主题",
                  "prerequisiteSlugs":[]
                }]
              },
              {
                "slug":"function-properties",
                "name":"函数性质",
                "sourceLocator":"函数性质主题",
                "knowledgePoints":[{
                  "slug":"read-monotonicity",
                  "name":"从图象读取函数单调性",
                  "aliases":[],
                  "kind":"REPRESENTATION",
                  "boundary":"只读取图象已经呈现的增减变化。",
                  "sourceLocator":"函数性质主题",
                  "prerequisiteSlugs":["recognize-function-relation"]
                }]
              }
            ]
          }]
        }
        """.trimIndent()

    @Test
    fun currentSchemaSupportsNestedTopicsWithEmptyIntermediateLayers() {
        val pack = ReviewedKnowledgePackJsonCodec.decode(nestedTopicPack())

        val topics = pack.nodes.filter { it.granularity == KnowledgeNodeGranularity.TOPIC.name }
        assertEquals(4, topics.size)
        val byName = topics.associateBy { it.displayName }
        val vol = byName.getValue("数学必修第一册")
        val chap = byName.getValue("数学必修第一册·第三章")
        val theme = byName.getValue("数学必修第一册·第三章·函数")
        val leaf = byName.getValue("数学必修第一册·第三章·函数·单调性")
        assertEquals(null, vol.parentKnowledgeNodeId)
        assertEquals(vol.knowledgeNodeId, chap.parentKnowledgeNodeId)
        assertEquals(chap.knowledgeNodeId, theme.parentKnowledgeNodeId)
        assertEquals(theme.knowledgeNodeId, leaf.parentKnowledgeNodeId)
        val atomic = pack.nodes.single { it.granularity == KnowledgeNodeGranularity.ATOMIC.name }
        assertEquals(leaf.knowledgeNodeId, atomic.parentKnowledgeNodeId)
    }

    @Test
    fun nestedTopicParentSlugMustReferenceExistingTopic() {
        val failure = runCatching {
            ReviewedKnowledgePackJsonCodec.decode(
                nestedTopicPack().replace(
                    "\"parentSlug\":\"数学必修第一册·第三章\"",
                    "\"parentSlug\":\"不存在的主题\"",
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("reference a topic in the same subject"))
    }

    @Test
    fun nestedTopicHierarchyCannotContainCycles() {
        // 函数 的 parent 改为 单调性，而 单调性 的 parent 本就是 函数 —— 直接构成二环
        val failure = runCatching {
            ReviewedKnowledgePackJsonCodec.decode(
                nestedTopicPack().replace(
                    "\"parentSlug\":\"数学必修第一册·第三章\"",
                    "\"parentSlug\":\"数学必修第一册·第三章·函数·单调性\"",
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message.orEmpty().contains("must not contain cycles"))
    }

    private fun nestedTopicPack(): String =
        """
        {
          "schemaVersion":2,
          "packId":"moe-2025-math-reviewed-v1",
          "taxonomyVersion":"moe-2025-math-reviewed-v1",
          "sourceNamespace":"moe-2025-reviewed-v1",
          "reviewedAtEpochMillis":2,
          "sourceUri":"https://www.ictr.edu.cn/policy/cheng/p/11.html",
          "coverage":{
            "baselineId":"moe-high-school-2017-2025",
            "catalogLevel":"PARTIAL",
            "teachingSupportLevel":"PARTIAL"
          },
          "subjects":[{
            "subject":"MATH",
            "sourceFingerprint":"BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
            "topics":[
              {
                "slug":"数学必修第一册",
                "name":"数学必修第一册",
                "sourceLocator":"定位：数学必修第一册",
                "knowledgePoints":[]
              },
              {
                "slug":"数学必修第一册·第三章",
                "name":"数学必修第一册·第三章",
                "sourceLocator":"定位：数学必修第一册 第三章",
                "parentSlug":"数学必修第一册",
                "knowledgePoints":[]
              },
              {
                "slug":"数学必修第一册·第三章·函数",
                "name":"数学必修第一册·第三章·函数",
                "sourceLocator":"定位：数学必修第一册 第三章·函数",
                "parentSlug":"数学必修第一册·第三章",
                "knowledgePoints":[]
              },
              {
                "slug":"数学必修第一册·第三章·函数·单调性",
                "name":"数学必修第一册·第三章·函数·单调性",
                "sourceLocator":"定位：数学必修第一册 第三章·函数·单调性",
                "parentSlug":"数学必修第一册·第三章·函数",
                "knowledgePoints":[{
                  "slug":"read-monotonicity",
                  "name":"从图象读取函数单调性",
                  "aliases":[],
                  "kind":"REPRESENTATION",
                  "boundary":"只读取图象已经呈现的增减变化。",
                  "sourceLocator":"函数主题",
                  "prerequisiteSlugs":[]
                }]
              }
            ]
          }]
        }
        """.trimIndent()

    private fun licensedAdaptationSidecar(): String =
        """
        {
          "schemaVersion":2,
          "packId":"moe-2020-foundation-v1",
          "sources":[{
            "sourceId":"source:open:math:example",
            "subject":"MATH",
            "sourceType":"AUTHORIZED_EDUCATION_MATERIAL",
            "title":"开放数学教学资料",
            "publisher":"示例机构",
            "edition":"2026",
            "sourceUri":"https://example.org/math",
            "licenseStatus":"LICENSED",
            "contentFingerprint":"CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC",
            "importedAtEpochMillis":1784764800000,
            "contentUsePolicy":"ADAPTATION_ALLOWED",
            "licenseExpression":"CC-BY-NC-SA-4.0",
            "licenseUri":"https://creativecommons.org/licenses/by-nc-sa/4.0/",
            "attributionText":"示例机构《开放数学教学资料》，依 CC BY-NC-SA 4.0 改编。"
          }],
          "materials":[{
            "slug":"licensed-complete-worked-example",
            "subject":"MATH",
            "type":"WORKED_EXAMPLE",
            "title":"函数单调性的完整解答示例",
            "summaryMarkdown":"展示从题面到结论的完整解答。",
            "applicabilityMarkdown":"适用于当前题涉及函数图象单调性时。",
            "contentMarkdown":"例题与完整解答都可以保留，但该材料不能被布置、评分或加入复习队列。",
            "boundaryMarkdown":"只辅助解释学生当前提供的题。",
            "derivationKind":"LICENSED_ADAPTATION",
            "sourceId":"source:open:math:example",
            "sourceLocator":"函数性质·示例",
            "reviewedAtEpochMillis":1784764800000,
            "bindings":[{
              "knowledgeNodeId":"kb:moe-2020-foundation-v1:math:atomic:read-monotonicity-from-graph",
              "role":"PRIMARY"
            }]
          }]
        }
        """.trimIndent()

    private fun classpathResource(path: String): String =
        requireNotNull(
            requireNotNull(ReviewedKnowledgePackJsonCodecTest::class.java.classLoader)
                .getResourceAsStream(path),
        ).bufferedReader().use { it.readText() }
}
