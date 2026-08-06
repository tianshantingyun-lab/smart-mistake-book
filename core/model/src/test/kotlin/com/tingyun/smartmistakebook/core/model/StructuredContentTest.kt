package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredContentTest {
    @Test
    fun `inline parser supports only the declared formatting subset`() {
        val tokens = SafeInlineMarkdown.parse(
            "普通 **重点** *提示* `x + 1`，且 \$f(x)=x^2\$\n下一行",
        )

        assertTrue(tokens.contains(InlineToken.Strong("重点")))
        assertTrue(tokens.contains(InlineToken.Emphasis("提示")))
        assertTrue(tokens.contains(InlineToken.Code("x + 1")))
        assertTrue(tokens.contains(InlineToken.Formula("f(x)=x^2")))
        assertTrue(tokens.contains(InlineToken.LineBreak))
    }

    @Test
    fun `escaped markers and paragraph boundaries cannot be reinterpreted by later text`() {
        assertEquals(
            listOf(
                InlineToken.Text("价格 $5"),
                InlineToken.LineBreak,
                InlineToken.LineBreak,
                InlineToken.Text("由 "),
                InlineToken.Formula("x"),
                InlineToken.Text(" 得出"),
            ),
            SafeInlineMarkdown.parse("价格 \\$5\n\n由 \$x\$ 得出"),
        )
        assertFalse(
            SafeInlineMarkdown.parse("已稳定 *\n\n后续*")
                .any { it is InlineToken.Emphasis },
        )
    }

    @Test
    fun `active content and remote images fall back to inert text`() {
        val candidates = listOf(
            "<b>不能执行</b> **也不解析强调**",
            "![远程图](https://example.com/a.png) **仍是文本**",
            "[点击](javascript:alert(1)) **仍是文本**",
            "![内联图](data:image/png;base64,abc) **仍是文本**",
        )

        candidates.forEach { candidate ->
            val tokens = SafeInlineMarkdown.parse(candidate)
            assertTrue(tokens.all { it is InlineToken.Text || it is InlineToken.LineBreak })
            assertTrue(SafeInlineMarkdown.requiresPlainTextFallback(candidate))
        }
    }

    @Test
    fun `unknown blocks become bounded literal paragraphs`() {
        val raw = QuestionDocument(
            id = "question",
            blocks = listOf(
                ContentBlock.Unknown(
                    id = "future",
                    type = "remote-image",
                    fallbackText = "**原始描述**",
                ),
            ),
        )

        val result = StructuredContentSanitizer.sanitize(raw)
        val paragraph = result.document.blocks.single() as ContentBlock.Paragraph

        assertEquals("＊＊原始描述＊＊", paragraph.markdown)
        assertTrue(
            result.issues.any { it.code == StructuredContentIssueCode.UNKNOWN_BLOCK_AS_TEXT },
        )
        assertTrue(SafeInlineMarkdown.parse(paragraph.markdown).all { it is InlineToken.Text })
    }

    @Test
    fun `sanitizer enforces document text and choice ceilings`() {
        val oversized = QuestionDocument(
            id = "question",
            blocks = listOf(
                ContentBlock.Paragraph("paragraph", "字".repeat(StructuredContentLimits.MAX_TEXT_CHARS + 1)),
                ContentBlock.ChoiceGroup(
                    id = "choices",
                    promptMarkdown = "请选择",
                    choices = List(StructuredContentLimits.MAX_CHOICES + 3) { index ->
                        StructuredChoice("choice-$index", "选项 $index")
                    },
                ),
            ),
        )

        val result = StructuredContentSanitizer.sanitize(oversized)
        val paragraph = result.document.blocks[0] as ContentBlock.Paragraph
        val choiceGroup = result.document.blocks[1] as ContentBlock.ChoiceGroup

        assertEquals(StructuredContentLimits.MAX_TEXT_CHARS, paragraph.markdown.length)
        assertEquals(StructuredContentLimits.MAX_CHOICES, choiceGroup.choices.size)
        assertTrue(result.issues.any { it.code == StructuredContentIssueCode.TEXT_LIMIT_EXCEEDED })
        assertTrue(result.issues.any { it.code == StructuredContentIssueCode.CHOICE_LIMIT_EXCEEDED })
    }

    @Test
    fun `sanitizer drops blocks beyond the resource ceiling`() {
        val raw = QuestionDocument(
            id = "question",
            blocks = List(StructuredContentLimits.MAX_BLOCKS + 4) { index ->
                ContentBlock.Paragraph("block-$index", "第 $index 段")
            },
        )

        val result = StructuredContentSanitizer.sanitize(raw)

        assertEquals(StructuredContentLimits.MAX_BLOCKS, result.document.blocks.size)
        assertTrue(result.issues.any { it.code == StructuredContentIssueCode.BLOCK_LIMIT_EXCEEDED })
    }

    @Test
    fun `sanitizer bounds figure points and coordinates`() {
        val xAxis = FigureAxis(0.0, 10.0)
        val yAxis = FigureAxis(-5.0, 5.0)
        val raw = QuestionDocument(
            id = "question",
            blocks = listOf(
                ContentBlock.Figure(
                    id = "figure",
                    alternativeText = "函数折线图",
                    schema = FigureSchema.Cartesian(
                        xAxis = xAxis,
                        yAxis = yAxis,
                        polylines = listOf(
                            FigurePolyline(
                                id = "line",
                                points = List(StructuredContentLimits.MAX_POINTS_PER_POLYLINE + 20) {
                                    FigureCoordinate(x = it.toDouble(), y = it.toDouble())
                                },
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = StructuredContentSanitizer.sanitize(raw)
        val figure = result.document.blocks.single() as ContentBlock.Figure
        val schema = figure.schema as FigureSchema.Cartesian

        assertEquals(StructuredContentLimits.MAX_POINTS_PER_POLYLINE, schema.polylines.single().points.size)
        assertTrue(schema.polylines.single().points.all { it.x in 0.0..10.0 && it.y in -5.0..5.0 })
        assertTrue(result.issues.any { it.code == StructuredContentIssueCode.FIGURE_LIMIT_EXCEEDED })
    }

    @Test
    fun `invalid axes degrade the whole figure to alternative text`() {
        val raw = QuestionDocument(
            id = "question",
            blocks = listOf(
                ContentBlock.Figure(
                    id = "figure",
                    alternativeText = "坐标轴范围无效",
                    schema = FigureSchema.Cartesian(
                        xAxis = FigureAxis(1.0, 1.0),
                        yAxis = FigureAxis(0.0, 1.0),
                    ),
                ),
            ),
        )

        val result = StructuredContentSanitizer.sanitize(raw)

        assertTrue(result.document.blocks.single() is ContentBlock.Paragraph)
        assertTrue(result.issues.any { it.code == StructuredContentIssueCode.INVALID_FIGURE_AS_TEXT })
    }

    @Test
    fun `non finite visible coordinates degrade the figure to alternative text`() {
        val raw = QuestionDocument(
            id = "question",
            blocks = listOf(
                ContentBlock.Figure(
                    id = "figure",
                    alternativeText = "存在无效坐标",
                    schema = FigureSchema.Cartesian(
                        xAxis = FigureAxis(0.0, 1.0),
                        yAxis = FigureAxis(0.0, 1.0),
                        points = listOf(FigurePoint(FigureCoordinate(Double.NaN, 0.5))),
                    ),
                ),
            ),
        )

        val result = StructuredContentSanitizer.sanitize(raw)

        assertTrue(result.document.blocks.single() is ContentBlock.Paragraph)
        assertTrue(result.issues.any { it.code == StructuredContentIssueCode.INVALID_FIGURE_AS_TEXT })
    }

    @Test
    fun `formula sanitizer preserves known commands and neutralizes unknown commands`() {
        val source = "\\frac{1}{2} + \\href{javascript:alert(1)}{x}"
        val sanitized = RestrictedFormulaText.sanitize(source)

        assertTrue(sanitized.contains("\\frac"))
        assertTrue(sanitized.contains("⧵href"))
        assertFalse(sanitized.contains("\\href"))
        assertTrue(RestrictedFormulaText.hasUnsupportedCommand(source))
    }

    @Test
    fun `readable math text preserves operator grouping and removes inert delimiters`() {
        val source =
            "函数 \$f(x)=x^3-3x+1\$；椭圆 \$\\frac{x^2}{25}+\\frac{y^2}{9}=1\$"

        assertEquals(
            "函数 f(x)=x³-3x+1；椭圆 (x²)/(25)+(y²)/(9)=1",
            ReadableMathText.inlineMarkdown(source),
        )
    }

    @Test
    fun `readable math text handles chemistry subscripts and equilibrium arrows`() {
        val source = "\$N_2O_4(g) \\rightleftharpoons 2NO_2(g)\$"

        assertEquals("N₂O₄(g) ⇌ 2NO₂(g)", ReadableMathText.inlineMarkdown(source))
    }

    @Test
    fun `readable math text handles unbraced vector atoms from the physics fixture`() {
        val source =
            "对正电荷使用 \$\\vec F=q\\vec v\\times\\vec B\$：速度向右、磁场向里，"

        assertEquals(
            "对正电荷使用 F⃗=qv⃗×B⃗：速度向右、磁场向里，",
            ReadableMathText.inlineMarkdown(source),
        )
    }

    @Test
    fun `readable math text keeps unsupported scripts explicit`() {
        val source = "\$x^{ab}+a_i\$"

        assertEquals("x^(ab)+a_(i)", ReadableMathText.inlineMarkdown(source))
    }

    @Test
    fun `duplicate identifiers are repaired deterministically`() {
        val raw = QuestionDocument(
            id = "question",
            blocks = listOf(
                ContentBlock.Paragraph("same", "一"),
                ContentBlock.Paragraph("same", "二"),
                ContentBlock.Paragraph("", "三"),
            ),
        )

        val result = StructuredContentSanitizer.sanitize(raw)

        assertEquals(listOf("same", "same-2", "block-2"), result.document.blocks.map(ContentBlock::id))
        assertEquals(3, result.document.blocks.map(ContentBlock::id).distinct().size)
    }

    @Test
    fun `document text budget bounds aggregate rendered content`() {
        val raw = QuestionDocument(
            id = "question",
            title = "题".repeat(StructuredContentLimits.MAX_TITLE_CHARS),
            blocks = List(8) { index ->
                ContentBlock.Paragraph(
                    id = "paragraph-$index",
                    markdown = "字".repeat(StructuredContentLimits.MAX_TEXT_CHARS),
                )
            },
        )

        val result = StructuredContentSanitizer.sanitize(raw)
        val renderedCharacters = result.document.title.orEmpty().length +
            result.document.blocks.sumOf { (it as ContentBlock.Paragraph).markdown.length }

        assertTrue(renderedCharacters <= StructuredContentLimits.MAX_DOCUMENT_TEXT_CHARS)
        assertTrue(
            result.issues.any {
                it.code == StructuredContentIssueCode.DOCUMENT_TEXT_BUDGET_EXCEEDED
            },
        )
    }

    @Test
    fun `document choice budget is shared by every choice group`() {
        val raw = QuestionDocument(
            id = "question",
            blocks = List(5) { groupIndex ->
                ContentBlock.ChoiceGroup(
                    id = "group-$groupIndex",
                    promptMarkdown = "请选择",
                    choices = List(StructuredContentLimits.MAX_CHOICES) { choiceIndex ->
                        StructuredChoice("choice-$choiceIndex", "选项 $choiceIndex")
                    },
                )
            },
        )

        val result = StructuredContentSanitizer.sanitize(raw)
        val choiceCount = result.document.blocks
            .filterIsInstance<ContentBlock.ChoiceGroup>()
            .sumOf { it.choices.size }

        assertEquals(StructuredContentLimits.MAX_DOCUMENT_CHOICES, choiceCount)
        assertTrue(
            result.issues.any {
                it.code == StructuredContentIssueCode.DOCUMENT_CHOICE_BUDGET_EXCEEDED
            },
        )
    }

    @Test
    fun `document table cell budget bounds padded table cells`() {
        fun table(id: String) = ContentBlock.Figure(
            id = id,
            alternativeText = "符号表",
            schema = FigureSchema.SymbolTable(
                headers = List(StructuredContentLimits.MAX_TABLE_COLUMNS) { "列 $it" },
                rows = List(StructuredContentLimits.MAX_TABLE_ROWS) { row ->
                    List(StructuredContentLimits.MAX_TABLE_COLUMNS) { column -> "$row-$column" }
                },
            ),
        )
        val result = StructuredContentSanitizer.sanitize(
            QuestionDocument("question", blocks = listOf(table("one"), table("two"))),
        )
        val renderedCells = result.document.blocks
            .filterIsInstance<ContentBlock.Figure>()
            .map { it.schema }
            .filterIsInstance<FigureSchema.SymbolTable>()
            .sumOf { table -> table.headers.size * (table.rows.size + 1) }

        assertTrue(renderedCells <= StructuredContentLimits.MAX_DOCUMENT_TABLE_CELLS)
        assertTrue(
            result.issues.any {
                it.code == StructuredContentIssueCode.DOCUMENT_TABLE_CELL_BUDGET_EXCEEDED
            },
        )
    }

    @Test
    fun `document figure primitive budget bounds plotted objects`() {
        val rawFigure = ContentBlock.Figure(
            id = "dense-figure",
            alternativeText = "密集折线",
            schema = FigureSchema.Cartesian(
                xAxis = FigureAxis(0.0, 200.0),
                yAxis = FigureAxis(0.0, 200.0),
                polylines = List(StructuredContentLimits.MAX_POLYLINES) { line ->
                    FigurePolyline(
                        id = "line-$line",
                        points = List(StructuredContentLimits.MAX_POINTS_PER_POLYLINE) { point ->
                            FigureCoordinate(point.toDouble(), point.toDouble())
                        },
                    )
                },
            ),
        )

        val result = StructuredContentSanitizer.sanitize(
            QuestionDocument("question", blocks = listOf(rawFigure)),
        )
        val figure = result.document.blocks.single() as ContentBlock.Figure
        val schema = figure.schema as FigureSchema.Cartesian
        val basePrimitives = 4 + ((schema.xAxis.tickCount + 1) + (schema.yAxis.tickCount + 1)) * 2
        val renderedPrimitives = basePrimitives + schema.polylines.sumOf { it.points.size * 2 }

        assertTrue(renderedPrimitives <= StructuredContentLimits.MAX_DOCUMENT_FIGURE_PRIMITIVES)
        assertTrue(
            result.issues.any {
                it.code == StructuredContentIssueCode.DOCUMENT_FIGURE_PRIMITIVE_BUDGET_EXCEEDED
            },
        )
    }

    @Test
    fun `overflowing axis span degrades before pixel mapping`() {
        val raw = QuestionDocument(
            id = "question",
            blocks = listOf(
                ContentBlock.Figure(
                    id = "figure",
                    alternativeText = "极端坐标轴",
                    schema = FigureSchema.Cartesian(
                        xAxis = FigureAxis(-Double.MAX_VALUE, Double.MAX_VALUE),
                        yAxis = FigureAxis(0.0, 1.0),
                    ),
                ),
            ),
        )

        val result = StructuredContentSanitizer.sanitize(raw)

        assertTrue(result.document.blocks.single() is ContentBlock.Paragraph)
        assertTrue(result.issues.any { it.code == StructuredContentIssueCode.INVALID_FIGURE_AS_TEXT })
    }

    @Test
    fun `unknown nested figure schema degrades to literal text`() {
        val raw = QuestionDocument(
            id = "question",
            blocks = listOf(
                ContentBlock.Figure(
                    id = "figure",
                    alternativeText = "",
                    schema = FigureSchema.Unknown(
                        type = "model-generated-image",
                        fallbackText = "**图示暂不可用**",
                    ),
                ),
            ),
        )

        val result = StructuredContentSanitizer.sanitize(raw)
        val paragraph = result.document.blocks.single() as ContentBlock.Paragraph

        assertEquals("＊＊图示暂不可用＊＊", paragraph.markdown)
        assertTrue(
            result.issues.any {
                it.code == StructuredContentIssueCode.UNKNOWN_FIGURE_SCHEMA_AS_TEXT
            },
        )
    }

    @Test
    fun `bidi controls are removed from every sanitized text path`() {
        val dangerous = "正常\u202Ecod.exe\u2066结束\u206F\u200F"
        val result = StructuredContentSanitizer.sanitize(
            QuestionDocument(
                id = "question",
                title = dangerous,
                blocks = listOf(ContentBlock.Paragraph("paragraph", dangerous)),
            ),
        )
        val paragraph = result.document.blocks.single() as ContentBlock.Paragraph

        assertEquals("正常cod.exe结束", result.document.title)
        assertEquals("正常cod.exe结束", paragraph.markdown)
        assertFalse(paragraph.markdown.any { it in '\u202A'..'\u202E' || it in '\u2066'..'\u206F' })
    }

    @Test
    fun `zero width and combining characters are normalized away from sanitized text`() {
        val dangerous = "e\u0301\u200B函数\uFEFFa\u0301\u200D"
        val result =
            StructuredContentSanitizer.sanitize(
                QuestionDocument(
                    id = "question",
                    title = dangerous,
                    blocks = listOf(ContentBlock.Paragraph("paragraph", dangerous)),
                ),
            )
        val paragraph = result.document.blocks.single() as ContentBlock.Paragraph

        assertEquals("é函数á", result.document.title)
        assertEquals("é函数á", paragraph.markdown)
        assertFalse(
            paragraph.markdown.any { character ->
                character == '\u00AD' ||
                    character == '\u180E' ||
                    character == '\u200B' ||
                    character == '\u200C' ||
                    character == '\u200D' ||
                    character == '\u2060' ||
                    character == '\uFEFF'
            },
        )
    }

    @Test
    fun `huge zero width input is bounded before normalization and leaves no residue`() {
        val dangerous = "\u200B".repeat(100_000) + "函数"
        val result =
            StructuredContentSanitizer.sanitize(
                QuestionDocument(
                    id = "question",
                    blocks = listOf(ContentBlock.Paragraph("paragraph", dangerous)),
                ),
            )
        val paragraph = result.document.blocks.single() as ContentBlock.Paragraph

        assertTrue(paragraph.markdown.length <= StructuredContentLimits.MAX_TEXT_CHARS)
        assertEquals("函数", paragraph.markdown)
        assertFalse(paragraph.markdown.contains('\u200B'))
    }

    @Test
    fun `zero width prefixes cannot swallow visible text in any sanitization path`() {
        val prefix = "\u200B".repeat(100_000)

        assertEquals(
            listOf(
                InlineToken.Text("函数"),
                InlineToken.LineBreak,
                InlineToken.Text("f(x)=x^2"),
            ),
            SafeInlineMarkdown.parse("${prefix}函数\nf(x)=x^2"),
        )
        assertEquals("函数", SafeInlineMarkdown.literal("${prefix}函数"))
        assertTrue(SafeInlineMarkdown.requiresPlainTextFallback("${prefix}<b>不能执行</b>"))
        assertTrue(
            SafeInlineMarkdown.parse("${prefix}<b>不能执行</b> **也不解析强调**")
                .all { it is InlineToken.Text || it is InlineToken.LineBreak },
        )
        assertTrue(RestrictedFormulaText.sanitize("${prefix}\\frac{1}{2}").startsWith("\\frac"))
        assertTrue(RestrictedFormulaText.hasUnsupportedCommand("${prefix}\\href{x}{y}"))

        val result = StructuredContentSanitizer.sanitize(
            QuestionDocument(
                id = "${prefix}question",
                title = "${prefix}标题",
                blocks = listOf(
                    ContentBlock.Figure(
                        id = "${prefix}figure",
                        alternativeText = "${prefix}函数折线图",
                        schema = FigureSchema.SymbolTable(
                            headers = listOf("${prefix}列"),
                            rows = listOf(listOf("${prefix}值")),
                        ),
                    ),
                    ContentBlock.ChoiceGroup(
                        id = "${prefix}choices",
                        promptMarkdown = "${prefix}请选择",
                        choices = listOf(StructuredChoice("${prefix}choice", "${prefix}选项")),
                    ),
                    ContentBlock.Unknown(
                        id = "${prefix}unknown",
                        type = "future-block",
                        fallbackText = "${prefix}未知内容兜底",
                    ),
                ),
            ),
        )

        assertEquals("question", result.document.id)
        assertEquals("标题", result.document.title)
        val figure = result.document.blocks[0] as ContentBlock.Figure
        assertEquals("函数折线图", figure.alternativeText)
        assertEquals(listOf("列"), (figure.schema as FigureSchema.SymbolTable).headers)
        val choiceGroup = result.document.blocks[1] as ContentBlock.ChoiceGroup
        assertEquals("请选择", choiceGroup.promptMarkdown)
        assertEquals("选项", choiceGroup.choices.single().markdown)
        val paragraph = result.document.blocks[2] as ContentBlock.Paragraph
        assertEquals("未知内容兜底", paragraph.markdown)
    }

    @Test
    fun `empty figure descriptions and choice text receive accessible fallbacks`() {
        val result = StructuredContentSanitizer.sanitize(
            QuestionDocument(
                id = "question",
                blocks = listOf(
                    ContentBlock.Figure(
                        id = "figure",
                        alternativeText = "",
                        schema = FigureSchema.Cartesian(
                            xAxis = FigureAxis(0.0, 1.0),
                            yAxis = FigureAxis(0.0, 1.0),
                        ),
                    ),
                    ContentBlock.ChoiceGroup(
                        id = "choices",
                        promptMarkdown = "选择",
                        choices = listOf(StructuredChoice("empty", "")),
                    ),
                ),
            ),
        )
        val figure = result.document.blocks[0] as ContentBlock.Figure
        val choices = result.document.blocks[1] as ContentBlock.ChoiceGroup

        assertEquals("未提供图示说明", figure.alternativeText)
        assertEquals("未提供选项内容", choices.choices.single().markdown)
        assertTrue(
            result.issues.any {
                it.code == StructuredContentIssueCode.EMPTY_FIGURE_DESCRIPTION_REPLACED
            },
        )
        assertTrue(
            result.issues.any {
                it.code == StructuredContentIssueCode.EMPTY_CHOICE_CONTENT_REPLACED
            },
        )
    }

    @Test
    fun `empty choice groups remain non interactive and are explicitly reported`() {
        val result = StructuredContentSanitizer.sanitize(
            QuestionDocument(
                id = "question",
                blocks = listOf(
                    ContentBlock.ChoiceGroup(
                        id = "choices",
                        promptMarkdown = "请选择",
                        choices = emptyList(),
                    ),
                ),
            ),
        )
        val group = result.document.blocks.single() as ContentBlock.ChoiceGroup

        assertTrue(group.choices.isEmpty())
        assertTrue(
            result.issues.any {
                it.code == StructuredContentIssueCode.EMPTY_CHOICE_GROUP_RENDERED_AS_STATUS
            },
        )
    }

    @Test
    fun `literal text neutralizes every active inline markup boundary`() {
        val literal = SafeInlineMarkdown.literal(
            "<script> javascript:alert(1) ![题图](https://example.com/a.png) **原文**",
        )

        assertFalse(SafeInlineMarkdown.requiresPlainTextFallback(literal))
        assertEquals(
            "＜script＞ javascript：alert(1) ！[题图](https://example.com/a.png) ＊＊原文＊＊",
            literal,
        )
        assertEquals("x<3 且 y>1", SafeInlineMarkdown.literal("x<3 且 y>1"))
    }
}
