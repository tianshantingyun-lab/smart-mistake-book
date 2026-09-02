package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.model.ImagePipelineClassifyInput
import com.tingyun.smartmistakebook.core.model.ImagePipelineProblemKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiImagePipelineClassifyAdapterTest {

    @Test
    fun `prompt asks for classification and transcription`() {
        val prompt = OpenAiModelTaskAdapters.prompt(
            ImagePipelineClassifyInput(
                sourceAssetId = "asset-1",
                imageWidth = 1200,
                imageHeight = 1600,
            ),
        )

        assertTrue(prompt.contains("WITH_FIGURE"))
        assertTrue(prompt.contains("TEXT_ONLY"))
        assertTrue(prompt.contains("textMarkdown"))
        assertTrue(prompt.contains("formulas"))
        assertTrue(prompt.contains("1200x1600"))
    }

    @Test
    fun `parses a with-figure classification`() {
        val payload = Json.parseToJsonElement(
            """
            {
              "problemKind":"WITH_FIGURE",
              "textMarkdown":"已知函数 f(x)=ax+b，求其单调区间。",
              "formulas":["f(x)=ax+b"]
            }
            """.trimIndent(),
        ).jsonObject

        val output = OpenAiModelTaskAdapters.parse(payload, input(), "test-model") as
            com.tingyun.smartmistakebook.core.model.ImagePipelineClassifyOutput

        assertEquals(ImagePipelineProblemKind.WITH_FIGURE, output.problemKind)
        assertEquals("已知函数 f(x)=ax+b，求其单调区间。", output.textMarkdown)
        assertEquals(listOf("f(x)=ax+b"), output.formulas)
        assertEquals("test-model", output.modelVersion)
    }

    @Test
    fun `parses a text-only classification with empty formulas`() {
        val payload = Json.parseToJsonElement(
            """{"problemKind":"TEXT_ONLY","textMarkdown":"纯文字题干","formulas":[]}""",
        ).jsonObject

        val output = OpenAiModelTaskAdapters.parse(payload, input(), "test-model") as
            com.tingyun.smartmistakebook.core.model.ImagePipelineClassifyOutput

        assertEquals(ImagePipelineProblemKind.TEXT_ONLY, output.problemKind)
        assertEquals("纯文字题干", output.textMarkdown)
        assertTrue(output.formulas.isEmpty())
    }

    @Test
    fun `rejects an unknown problem kind`() {
        val payload = Json.parseToJsonElement(
            """{"problemKind":"UNKNOWN","textMarkdown":"x","formulas":[]}""",
        ).jsonObject

        val failure = runCatching {
            OpenAiModelTaskAdapters.parse(payload, input(), "test-model")
        }.exceptionOrNull()

        assertTrue(failure is InvalidModelResponseException)
    }

    private fun input() = ImagePipelineClassifyInput(
        sourceAssetId = "asset-1",
        imageWidth = 100,
        imageHeight = 200,
    )
}
