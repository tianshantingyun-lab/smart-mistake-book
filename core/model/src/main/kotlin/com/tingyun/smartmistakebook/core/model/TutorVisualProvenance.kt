package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A stable textual leaf inside the committed question document. */
@Serializable
enum class TutorVisualSourceTextField {
    PARAGRAPH_MARKDOWN,
    FORMULA_LATEX,
    FORMULA_ALTERNATIVE_TEXT,
    CHOICE_PROMPT,
    CHOICE_MARKDOWN,
    FIGURE_TITLE,
    FIGURE_ALTERNATIVE_TEXT,
    SYMBOL_TABLE_HEADER,
    SYMBOL_TABLE_CELL,
    CARTESIAN_X_AXIS_MINIMUM,
    CARTESIAN_X_AXIS_MAXIMUM,
    CARTESIAN_Y_AXIS_MINIMUM,
    CARTESIAN_Y_AXIS_MAXIMUM,
    CARTESIAN_POLYLINE_POINT_X,
    CARTESIAN_POLYLINE_POINT_Y,
    CARTESIAN_POINT_X,
    CARTESIAN_POINT_Y,
    UNKNOWN_FALLBACK,
}

/**
 * Locates one deterministic string without allowing the model to submit arbitrary source text.
 * [indices] is empty for scalar fields, contains a choice/header index for one-dimensional fields,
 * and contains row/column for a symbol-table cell.
 */
@Serializable
data class TutorVisualSourceTextLocator(
    val field: TutorVisualSourceTextField,
    val indices: List<Int> = emptyList(),
) {
    init {
        require(indices.size <= MAX_INDICES && indices.all { it >= 0 })
        val requiredSize = when (field) {
            TutorVisualSourceTextField.CHOICE_MARKDOWN,
            TutorVisualSourceTextField.SYMBOL_TABLE_HEADER,
            TutorVisualSourceTextField.CARTESIAN_POINT_X,
            TutorVisualSourceTextField.CARTESIAN_POINT_Y,
            -> 1
            TutorVisualSourceTextField.SYMBOL_TABLE_CELL,
            TutorVisualSourceTextField.CARTESIAN_POLYLINE_POINT_X,
            TutorVisualSourceTextField.CARTESIAN_POLYLINE_POINT_Y,
            -> 2
            else -> 0
        }
        require(indices.size == requiredSize) {
            "Tutor visual source locator has an invalid index count"
        }
    }

    companion object {
        private const val MAX_INDICES = 2
    }
}

/**
 * A locally minted scalar fact. Models receive fact ids and may reference them, but cannot create
 * new facts. The hash binds the scalar to its exact committed text span and canonical source image.
 */
@Serializable
data class TutorVisualSourceFact(
    val factId: String,
    val documentId: String,
    val blockId: String,
    val locator: TutorVisualSourceTextLocator,
    val startUtf16: Int,
    val endUtf16Exclusive: Int,
    val literal: String,
    val value: Double,
    val unit: String? = null,
    val dimension: TutorVisualDimension,
    val sourceAssetId: String,
    val sourceAssetSha256: String,
    val sourceRegion: NormalizedSourceRegion,
    val anchorSha256: String,
) {
    init {
        factId.requireTutorSceneId("Tutor visual source fact id")
        documentId.requireSafeModelText("Tutor visual source document id", MAX_ID_CHARS, false)
        blockId.requireSafeModelText("Tutor visual source block id", MAX_ID_CHARS, false)
        sourceAssetId.requireSafeModelText("Tutor visual source asset id", MAX_ID_CHARS, false)
        require(startUtf16 >= 0 && endUtf16Exclusive > startUtf16)
        literal.requireSafeModelText("Tutor visual source literal", MAX_LITERAL_CHARS, true)
        require(value.isFinite() && kotlin.math.abs(value) <= TutorVisualVariable.MAX_ABS_VALUE)
        unit?.requireSafeModelText("Tutor visual source unit", TutorVisualVariable.MAX_UNIT_CHARS, false)
        require(unit == null || TutorVisualUnitCatalog.resolve(unit, dimension) != null) {
            "Tutor visual source fact uses an unknown or dimensionally incompatible unit"
        }
        require(sourceAssetSha256.isLowercaseSha256())
        require(sourceRegion.isValidTutorVisualRegion())
        require(anchorSha256.isLowercaseSha256())
        require(anchorSha256 == TutorVisualSourceFactFingerprint.of(this)) {
            "Tutor visual source fact anchor does not match its content"
        }
    }

    companion object {
        const val MAX_LITERAL_CHARS = 160
        private const val MAX_ID_CHARS = 160

        fun create(
            factId: String,
            documentId: String,
            blockId: String,
            locator: TutorVisualSourceTextLocator,
            startUtf16: Int,
            endUtf16Exclusive: Int,
            literal: String,
            value: Double,
            unit: String? = null,
            dimension: TutorVisualDimension,
            sourceAssetId: String,
            sourceAssetSha256: String,
            sourceRegion: NormalizedSourceRegion,
        ): TutorVisualSourceFact {
            val anchor = TutorVisualSourceFactFingerprint.of(
                documentId = documentId,
                blockId = blockId,
                locator = locator,
                startUtf16 = startUtf16,
                endUtf16Exclusive = endUtf16Exclusive,
                literal = literal,
                value = value,
                unit = unit,
                dimension = dimension,
                sourceAssetId = sourceAssetId,
                sourceAssetSha256 = sourceAssetSha256,
                sourceRegion = sourceRegion,
            )
            return TutorVisualSourceFact(
                factId = factId,
                documentId = documentId,
                blockId = blockId,
                locator = locator,
                startUtf16 = startUtf16,
                endUtf16Exclusive = endUtf16Exclusive,
                literal = literal,
                value = value,
                unit = unit,
                dimension = dimension,
                sourceAssetId = sourceAssetId,
                sourceAssetSha256 = sourceAssetSha256,
                sourceRegion = sourceRegion,
                anchorSha256 = anchor,
            )
        }
    }
}

object TutorVisualSourceFactFingerprint {
    private const val DOMAIN = "tutor-visual-source-fact-v1"

    fun of(fact: TutorVisualSourceFact): String = of(
        documentId = fact.documentId,
        blockId = fact.blockId,
        locator = fact.locator,
        startUtf16 = fact.startUtf16,
        endUtf16Exclusive = fact.endUtf16Exclusive,
        literal = fact.literal,
        value = fact.value,
        unit = fact.unit,
        dimension = fact.dimension,
        sourceAssetId = fact.sourceAssetId,
        sourceAssetSha256 = fact.sourceAssetSha256,
        sourceRegion = fact.sourceRegion,
    )

    fun of(
        documentId: String,
        blockId: String,
        locator: TutorVisualSourceTextLocator,
        startUtf16: Int,
        endUtf16Exclusive: Int,
        literal: String,
        value: Double,
        unit: String?,
        dimension: TutorVisualDimension,
        sourceAssetId: String,
        sourceAssetSha256: String,
        sourceRegion: NormalizedSourceRegion,
    ): String = CanonicalSha256(DOMAIN)
        .field("documentId", documentId)
        .field("blockId", blockId)
        .field("field", locator.field.name)
        .field("indices", locator.indices.joinToString(","))
        .field("startUtf16", startUtf16)
        .field("endUtf16Exclusive", endUtf16Exclusive)
        .field("literal", literal)
        .field("valueBits", value.toBits().toString())
        .nullableField("unit", unit)
        .field("dimension", dimension.name)
        .field("sourceAssetId", sourceAssetId)
        .field("sourceAssetSha256", sourceAssetSha256)
        .field("regionLeftBits", sourceRegion.left.toBits().toString())
        .field("regionTopBits", sourceRegion.top.toBits().toString())
        .field("regionRightBits", sourceRegion.right.toBits().toString())
        .field("regionBottomBits", sourceRegion.bottom.toBits().toString())
        .finish()
}

/** Validates that locally persisted facts still point at the exact granted question and images. */
object TutorVisualSourceFactCatalog {
    fun requireValid(
        questionDocument: QuestionDocument,
        sourceAssets: List<CaptureSourceAssetRef>,
        sourceFacts: List<TutorVisualSourceFact>,
    ) {
        require(sourceFacts.size <= MAX_SOURCE_FACTS)
        require(sourceFacts.map(TutorVisualSourceFact::factId).distinct().size == sourceFacts.size)
        val assets = sourceAssets.associateBy(CaptureSourceAssetRef::assetId)
        sourceFacts.forEach { fact ->
            require(fact.documentId == questionDocument.id)
            val asset = assets[fact.sourceAssetId]
                ?: throw IllegalArgumentException("Tutor visual source fact uses an ungranted asset")
            require(asset.sha256 == fact.sourceAssetSha256)
            asset.selectedRegion?.let { granted ->
                require(granted.contains(fact.sourceRegion)) {
                    "Tutor visual source fact is outside its granted image region"
                }
            }
            val sourceText = questionDocument.resolveTutorVisualSourceText(fact.blockId, fact.locator)
            require(fact.endUtf16Exclusive <= sourceText.length)
            require(sourceText.substring(fact.startUtf16, fact.endUtf16Exclusive) == fact.literal) {
                "Tutor visual source fact no longer matches its question text span"
            }
            require(fact.literal.containsTutorVisualNumericValue(fact.value)) {
                "Tutor visual source fact value is not present in its bound text span"
            }
        }
    }

    const val MAX_SOURCE_FACTS = 512
}

data class TutorVisualUnitDefinition(
    val unit: String,
    val dimension: TutorVisualDimension,
    /** Null means the unit is displayable from a GIVEN fact but unsafe for local arithmetic. */
    val canonicalScale: Double?,
)

/** Shared deterministic unit vocabulary. Unknown units are never guessed. */
object TutorVisualUnitCatalog {
    private val definitions = listOf(
        unit("1", TutorVisualDimension.DIMENSIONLESS, 1.0),
        unit("%", TutorVisualDimension.DIMENSIONLESS, 0.01),
        unit("km", TutorVisualDimension.LENGTH, 1_000.0),
        unit("dm", TutorVisualDimension.LENGTH, 0.1),
        unit("cm", TutorVisualDimension.LENGTH, 0.01),
        unit("mm", TutorVisualDimension.LENGTH, 0.001),
        unit("μm", TutorVisualDimension.LENGTH, 1e-6),
        unit("µm", TutorVisualDimension.LENGTH, 1e-6),
        unit("um", TutorVisualDimension.LENGTH, 1e-6),
        unit("nm", TutorVisualDimension.LENGTH, 1e-9),
        unit("m", TutorVisualDimension.LENGTH, 1.0),
        unit("ms", TutorVisualDimension.TIME, 0.001),
        unit("min", TutorVisualDimension.TIME, 60.0),
        unit("h", TutorVisualDimension.TIME, 3_600.0),
        unit("s", TutorVisualDimension.TIME, 1.0),
        unit("kg", TutorVisualDimension.MASS, 1.0),
        unit("mg", TutorVisualDimension.MASS, 1e-6),
        unit("g", TutorVisualDimension.MASS, 0.001),
        unit("t", TutorVisualDimension.MASS, 1_000.0),
        unit("mA", TutorVisualDimension.ELECTRIC_CURRENT, 0.001),
        unit("μA", TutorVisualDimension.ELECTRIC_CURRENT, 1e-6),
        unit("µA", TutorVisualDimension.ELECTRIC_CURRENT, 1e-6),
        unit("uA", TutorVisualDimension.ELECTRIC_CURRENT, 1e-6),
        unit("A", TutorVisualDimension.ELECTRIC_CURRENT, 1.0),
        unit("°C", TutorVisualDimension.TEMPERATURE, null),
        unit("℃", TutorVisualDimension.TEMPERATURE, null),
        unit("K", TutorVisualDimension.TEMPERATURE, 1.0),
        unit("mmol", TutorVisualDimension.AMOUNT_OF_SUBSTANCE, 0.001),
        unit("mol", TutorVisualDimension.AMOUNT_OF_SUBSTANCE, 1.0),
        unit("rad", TutorVisualDimension.ANGLE, 1.0),
        unit("deg", TutorVisualDimension.ANGLE, kotlin.math.PI / 180.0),
        unit("°", TutorVisualDimension.ANGLE, kotlin.math.PI / 180.0),
        unit("km²", TutorVisualDimension.AREA, 1e6),
        unit("km^2", TutorVisualDimension.AREA, 1e6),
        unit("cm²", TutorVisualDimension.AREA, 1e-4),
        unit("cm^2", TutorVisualDimension.AREA, 1e-4),
        unit("mm²", TutorVisualDimension.AREA, 1e-6),
        unit("mm^2", TutorVisualDimension.AREA, 1e-6),
        unit("m²", TutorVisualDimension.AREA, 1.0),
        unit("m^2", TutorVisualDimension.AREA, 1.0),
        unit("cm³", TutorVisualDimension.VOLUME, 1e-6),
        unit("cm^3", TutorVisualDimension.VOLUME, 1e-6),
        unit("m³", TutorVisualDimension.VOLUME, 1.0),
        unit("m^3", TutorVisualDimension.VOLUME, 1.0),
        unit("mL", TutorVisualDimension.VOLUME, 1e-6),
        unit("ml", TutorVisualDimension.VOLUME, 1e-6),
        unit("L", TutorVisualDimension.VOLUME, 0.001),
        unit("l", TutorVisualDimension.VOLUME, 0.001),
        unit("km/h", TutorVisualDimension.SPEED, 1.0 / 3.6),
        unit("m/s²", TutorVisualDimension.ACCELERATION, 1.0),
        unit("m/s^2", TutorVisualDimension.ACCELERATION, 1.0),
        unit("m/s", TutorVisualDimension.SPEED, 1.0),
        unit("kN", TutorVisualDimension.FORCE, 1_000.0),
        unit("mN", TutorVisualDimension.FORCE, 0.001),
        unit("N", TutorVisualDimension.FORCE, 1.0),
        unit("MJ", TutorVisualDimension.ENERGY, 1e6),
        unit("kJ", TutorVisualDimension.ENERGY, 1_000.0),
        unit("J", TutorVisualDimension.ENERGY, 1.0),
        unit("MW", TutorVisualDimension.POWER, 1e6),
        unit("kW", TutorVisualDimension.POWER, 1_000.0),
        unit("W", TutorVisualDimension.POWER, 1.0),
        unit("MPa", TutorVisualDimension.PRESSURE, 1e6),
        unit("kPa", TutorVisualDimension.PRESSURE, 1_000.0),
        unit("cmHg", TutorVisualDimension.PRESSURE, 1_333.223_874_15),
        unit("mmHg", TutorVisualDimension.PRESSURE, 133.322_387_415),
        unit("atm", TutorVisualDimension.PRESSURE, 101_325.0),
        unit("bar", TutorVisualDimension.PRESSURE, 100_000.0),
        unit("Pa", TutorVisualDimension.PRESSURE, 1.0),
        unit("mV", TutorVisualDimension.VOLTAGE, 0.001),
        unit("kV", TutorVisualDimension.VOLTAGE, 1_000.0),
        unit("V", TutorVisualDimension.VOLTAGE, 1.0),
        unit("MΩ", TutorVisualDimension.RESISTANCE, 1e6),
        unit("kΩ", TutorVisualDimension.RESISTANCE, 1_000.0),
        unit("Ω", TutorVisualDimension.RESISTANCE, 1.0),
        unit("Mohm", TutorVisualDimension.RESISTANCE, 1e6),
        unit("kohm", TutorVisualDimension.RESISTANCE, 1_000.0),
        unit("ohm", TutorVisualDimension.RESISTANCE, 1.0),
        unit("mC", TutorVisualDimension.CHARGE, 0.001),
        unit("μC", TutorVisualDimension.CHARGE, 1e-6),
        unit("µC", TutorVisualDimension.CHARGE, 1e-6),
        unit("uC", TutorVisualDimension.CHARGE, 1e-6),
        unit("C", TutorVisualDimension.CHARGE, 1.0),
        unit("mmol/L", TutorVisualDimension.CONCENTRATION, 1.0),
        unit("mol/m³", TutorVisualDimension.CONCENTRATION, 1.0),
        unit("mol/m^3", TutorVisualDimension.CONCENTRATION, 1.0),
        unit("mol/L", TutorVisualDimension.CONCENTRATION, 1_000.0),
        unit("MHz", TutorVisualDimension.FREQUENCY, 1e6),
        unit("kHz", TutorVisualDimension.FREQUENCY, 1_000.0),
        unit("Hz", TutorVisualDimension.FREQUENCY, 1.0),
        unit("s⁻¹", TutorVisualDimension.FREQUENCY, 1.0),
        unit("s^-1", TutorVisualDimension.FREQUENCY, 1.0),
        unit("rad/s", TutorVisualDimension.OTHER, null),
        unit("T", TutorVisualDimension.OTHER, null),
    ).sortedByDescending { it.unit.length }

    fun resolve(unit: String?, dimension: TutorVisualDimension): TutorVisualUnitDefinition? {
        if (unit == null) {
            return when (dimension) {
                TutorVisualDimension.DIMENSIONLESS ->
                    TutorVisualUnitDefinition("", dimension, 1.0)
                TutorVisualDimension.ANGLE ->
                    TutorVisualUnitDefinition("", dimension, 1.0)
                else -> null
            }
        }
        return definitions.singleOrNull { it.unit == unit && it.dimension == dimension }
    }

    internal fun matchSuffix(text: String, start: Int): TutorVisualUnitDefinition? {
        val suffix = text.substring(start)
        val leadingWhitespace = suffix.indexOfFirst { !it.isWhitespace() }
            .let { if (it < 0) suffix.length else it }
        val candidate = suffix.substring(leadingWhitespace)
        return definitions.firstOrNull { definition ->
            candidate.startsWith(definition.unit) &&
                candidate.getOrNull(definition.unit.length).isSafeUnitBoundary()
        }
    }

    private fun Char?.isSafeUnitBoundary(): Boolean =
        this == null ||
            (!isAsciiLetterOrDigit() && this !in setOf('/', '*', '·', '⋅', '^', '²', '³', '⁻', '¹'))

    private fun unit(
        value: String,
        dimension: TutorVisualDimension,
        canonicalScale: Double?,
    ) = TutorVisualUnitDefinition(value, dimension, canonicalScale)
}

/**
 * Deterministically mints the only GIVEN ids a visual model is allowed to reference. The
 * extractor trusts committed block evidence, not model-authored coordinates or semantic guesses.
 */
object TutorVisualSourceFactExtractor {
    fun extract(
        capturedDocument: CapturedQuestionDocument,
        sourceAssets: List<CaptureSourceAssetRef>,
    ): List<TutorVisualSourceFact> {
        require(CapturedQuestionDocumentValidator.validateForCommit(capturedDocument).isEmpty()) {
            "Tutor visual source facts require a committed question document"
        }
        require(sourceAssets.map(CaptureSourceAssetRef::assetId).distinct().size == sourceAssets.size)
        val assets = sourceAssets.associateBy(CaptureSourceAssetRef::assetId)
        val evidenceByBlock = capturedDocument.blockEvidence.associateBy(QuestionBlockEvidence::blockId)

        val facts = capturedDocument.document.blocks.asSequence().flatMap { block ->
            val evidence = requireNotNull(evidenceByBlock[block.id])
            val asset = assets[evidence.sourceAssetId]
                ?: throw IllegalArgumentException("Tutor visual source evidence uses an ungranted asset")
            val region = requireNotNull(evidence.sourceRegion)
            block.tutorVisualSourceLeaves().asSequence().flatMap { leaf ->
                leaf.text.tutorVisualNumericMatches().mapNotNull { numeric ->
                    val unit = TutorVisualUnitCatalog.matchSuffix(leaf.text, numeric.endUtf16Exclusive)
                    if (unit == null && leaf.text.getOrNull(numeric.endUtf16Exclusive).isAsciiLetterOrDigit()) {
                        return@mapNotNull null
                    }
                    val unitStart = if (unit == null) {
                        numeric.endUtf16Exclusive
                    } else {
                        leaf.text.indexOfFirstNonWhitespace(numeric.endUtf16Exclusive)
                    }
                    val end = unit?.let { unitStart + it.unit.length } ?: numeric.endUtf16Exclusive
                    val literal = leaf.text.substring(numeric.startUtf16, end)
                    val dimension = unit?.dimension ?: TutorVisualDimension.DIMENSIONLESS
                    val anchor = TutorVisualSourceFactFingerprint.of(
                        documentId = capturedDocument.document.id,
                        blockId = block.id,
                        locator = leaf.locator,
                        startUtf16 = numeric.startUtf16,
                        endUtf16Exclusive = end,
                        literal = literal,
                        value = numeric.value,
                        unit = unit?.unit,
                        dimension = dimension,
                        sourceAssetId = asset.assetId,
                        sourceAssetSha256 = asset.sha256,
                        sourceRegion = region,
                    )
                    TutorVisualSourceFact.create(
                        factId = "vf_${anchor.take(32)}",
                        documentId = capturedDocument.document.id,
                        blockId = block.id,
                        locator = leaf.locator,
                        startUtf16 = numeric.startUtf16,
                        endUtf16Exclusive = end,
                        literal = literal,
                        value = numeric.value,
                        unit = unit?.unit,
                        dimension = dimension,
                        sourceAssetId = asset.assetId,
                        sourceAssetSha256 = asset.sha256,
                        sourceRegion = region,
                    )
                }
            }
        }.take(TutorVisualSourceFactCatalog.MAX_SOURCE_FACTS).toList()

        TutorVisualSourceFactCatalog.requireValid(
            capturedDocument.document,
            sourceAssets,
            facts,
        )
        return facts
    }
}

private data class TutorVisualSourceLeaf(
    val locator: TutorVisualSourceTextLocator,
    val text: String,
)

private data class TutorVisualNumericMatch(
    val startUtf16: Int,
    val endUtf16Exclusive: Int,
    val value: Double,
)

private fun ContentBlock.tutorVisualSourceLeaves(): List<TutorVisualSourceLeaf> = when (this) {
    is ContentBlock.Paragraph -> listOf(
        TutorVisualSourceLeaf(
            TutorVisualSourceTextLocator(TutorVisualSourceTextField.PARAGRAPH_MARKDOWN),
            markdown,
        ),
    )
    is ContentBlock.Formula -> listOf(
        TutorVisualSourceLeaf(
            TutorVisualSourceTextLocator(TutorVisualSourceTextField.FORMULA_LATEX),
            latex,
        ),
        TutorVisualSourceLeaf(
            TutorVisualSourceTextLocator(TutorVisualSourceTextField.FORMULA_ALTERNATIVE_TEXT),
            alternativeText,
        ),
    )
    is ContentBlock.ChoiceGroup -> buildList {
        add(
            TutorVisualSourceLeaf(
                TutorVisualSourceTextLocator(TutorVisualSourceTextField.CHOICE_PROMPT),
                promptMarkdown,
            ),
        )
        choices.forEachIndexed { index, choice ->
            add(
                TutorVisualSourceLeaf(
                    TutorVisualSourceTextLocator(
                        TutorVisualSourceTextField.CHOICE_MARKDOWN,
                        listOf(index),
                    ),
                    choice.markdown,
                ),
            )
        }
    }
    is ContentBlock.Figure -> buildList {
        title?.let {
            add(
                TutorVisualSourceLeaf(
                    TutorVisualSourceTextLocator(TutorVisualSourceTextField.FIGURE_TITLE),
                    it,
                ),
            )
        }
        add(
            TutorVisualSourceLeaf(
                TutorVisualSourceTextLocator(TutorVisualSourceTextField.FIGURE_ALTERNATIVE_TEXT),
                alternativeText,
            ),
        )
        (schema as? FigureSchema.SymbolTable)?.let { table ->
            table.headers.forEachIndexed { index, header ->
                add(
                    TutorVisualSourceLeaf(
                        TutorVisualSourceTextLocator(
                            TutorVisualSourceTextField.SYMBOL_TABLE_HEADER,
                            listOf(index),
                        ),
                        header,
                    ),
                )
            }
            table.rows.forEachIndexed { rowIndex, row ->
                row.forEachIndexed { columnIndex, cell ->
                    add(
                        TutorVisualSourceLeaf(
                            TutorVisualSourceTextLocator(
                                TutorVisualSourceTextField.SYMBOL_TABLE_CELL,
                                listOf(rowIndex, columnIndex),
                            ),
                            cell,
                        ),
                    )
                }
            }
        }
        (schema as? FigureSchema.Cartesian)?.let { chart ->
            add(chart.xAxis.minimum.tutorVisualCartesianLeaf(TutorVisualSourceTextField.CARTESIAN_X_AXIS_MINIMUM))
            add(chart.xAxis.maximum.tutorVisualCartesianLeaf(TutorVisualSourceTextField.CARTESIAN_X_AXIS_MAXIMUM))
            add(chart.yAxis.minimum.tutorVisualCartesianLeaf(TutorVisualSourceTextField.CARTESIAN_Y_AXIS_MINIMUM))
            add(chart.yAxis.maximum.tutorVisualCartesianLeaf(TutorVisualSourceTextField.CARTESIAN_Y_AXIS_MAXIMUM))
            chart.polylines.forEachIndexed { seriesIndex, polyline ->
                polyline.points.forEachIndexed { pointIndex, point ->
                    add(
                        point.x.tutorVisualCartesianLeaf(
                            TutorVisualSourceTextField.CARTESIAN_POLYLINE_POINT_X,
                            listOf(seriesIndex, pointIndex),
                        ),
                    )
                    add(
                        point.y.tutorVisualCartesianLeaf(
                            TutorVisualSourceTextField.CARTESIAN_POLYLINE_POINT_Y,
                            listOf(seriesIndex, pointIndex),
                        ),
                    )
                }
            }
            chart.points.forEachIndexed { pointIndex, point ->
                add(
                    point.coordinate.x.tutorVisualCartesianLeaf(
                        TutorVisualSourceTextField.CARTESIAN_POINT_X,
                        listOf(pointIndex),
                    ),
                )
                add(
                    point.coordinate.y.tutorVisualCartesianLeaf(
                        TutorVisualSourceTextField.CARTESIAN_POINT_Y,
                        listOf(pointIndex),
                    ),
                )
            }
        }
    }
    is ContentBlock.Unknown -> listOf(
        TutorVisualSourceLeaf(
            TutorVisualSourceTextLocator(TutorVisualSourceTextField.UNKNOWN_FALLBACK),
            fallbackText,
        ),
    )
}

private fun Double.tutorVisualCartesianLeaf(
    field: TutorVisualSourceTextField,
    indices: List<Int> = emptyList(),
) = TutorVisualSourceLeaf(
    locator = TutorVisualSourceTextLocator(field, indices),
    text = toString(),
)

private fun String.tutorVisualNumericMatches(): Sequence<TutorVisualNumericMatch> =
    TUTOR_VISUAL_NUMERIC_LITERAL.findAll(this).mapNotNull { match ->
        match.value.parseTutorVisualNumericValue()?.let { value ->
            TutorVisualNumericMatch(match.range.first, match.range.last + 1, value)
        }
    }

private fun String.indexOfFirstNonWhitespace(start: Int): Int {
    var index = start
    while (index < length && this[index].isWhitespace()) index += 1
    return index
}

private fun Char?.isAsciiLetterOrDigit(): Boolean =
    this != null && (this in '0'..'9' || this in 'a'..'z' || this in 'A'..'Z' || this == '_')

@Serializable
sealed interface TutorVisualValueProof {
    @Serializable
    @SerialName("given_source_fact")
    data class Given(
        val sourceFactId: String,
    ) : TutorVisualValueProof {
        init {
            sourceFactId.requireTutorSceneId("Tutor visual given source fact id")
        }
    }

    @Serializable
    @SerialName("derived_dag")
    data class Derived(
        val derivation: TutorVisualDerivation,
    ) : TutorVisualValueProof

    @Serializable
    @SerialName("illustrative_only")
    data class Illustrative(
        val purpose: TutorVisualIllustrativePurpose = TutorVisualIllustrativePurpose.ANIMATION_ONLY,
    ) : TutorVisualValueProof
}

@Serializable
enum class TutorVisualIllustrativePurpose {
    ANIMATION_ONLY,
    TREND_SHAPE_ONLY,
}

@Serializable
enum class TutorVisualDerivationOperation {
    VARIABLE,
    PARAMETER,
    ZERO,
    ONE,
    TWO,
    PI,
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    NEGATE,
    SIN,
    COS,
    SQRT,
    ABS,
    MIN,
    MAX,
    CLAMP,
    LERP,
}

@Serializable
data class TutorVisualDerivationNode(
    val nodeId: String,
    val operation: TutorVisualDerivationOperation,
    val inputNodeIds: List<String> = emptyList(),
    val variableId: String? = null,
) {
    init {
        nodeId.requireTutorSceneId("Tutor visual derivation node id")
        inputNodeIds.forEach { it.requireTutorSceneId("Tutor visual derivation input id") }
        val expectedInputs = when (operation) {
            TutorVisualDerivationOperation.VARIABLE,
            TutorVisualDerivationOperation.PARAMETER,
            TutorVisualDerivationOperation.ZERO,
            TutorVisualDerivationOperation.ONE,
            TutorVisualDerivationOperation.TWO,
            TutorVisualDerivationOperation.PI,
            -> 0
            TutorVisualDerivationOperation.NEGATE,
            TutorVisualDerivationOperation.SIN,
            TutorVisualDerivationOperation.COS,
            TutorVisualDerivationOperation.SQRT,
            TutorVisualDerivationOperation.ABS,
            -> 1
            TutorVisualDerivationOperation.ADD,
            TutorVisualDerivationOperation.SUBTRACT,
            TutorVisualDerivationOperation.MULTIPLY,
            TutorVisualDerivationOperation.DIVIDE,
            TutorVisualDerivationOperation.MIN,
            TutorVisualDerivationOperation.MAX,
            -> 2
            TutorVisualDerivationOperation.CLAMP,
            TutorVisualDerivationOperation.LERP,
            -> 3
        }
        require(inputNodeIds.size == expectedInputs)
        if (operation == TutorVisualDerivationOperation.VARIABLE) {
            requireNotNull(variableId).requireTutorSceneId("Tutor visual derivation variable id")
        } else {
            require(variableId == null)
        }
    }
}

@Serializable
data class TutorVisualDerivation(
    val rootNodeId: String,
    val nodes: List<TutorVisualDerivationNode>,
) {
    init {
        rootNodeId.requireTutorSceneId("Tutor visual derivation root id")
        require(nodes.isNotEmpty() && nodes.size <= MAX_NODES)
        require(nodes.map(TutorVisualDerivationNode::nodeId).distinct().size == nodes.size)
        require(nodes.any { it.nodeId == rootNodeId })
    }

    companion object {
        const val MAX_NODES = 64
    }
}

@Serializable
data class TutorVisualChartPointVariableProof(
    val xVariableId: String,
    val yVariableId: String,
) {
    init {
        xVariableId.requireTutorSceneId("Tutor visual chart x variable id")
        yVariableId.requireTutorSceneId("Tutor visual chart y variable id")
    }
}

@Serializable
sealed interface TutorVisualChartSeriesProof {
    @Serializable
    @SerialName("proven_points")
    data class ProvenPoints(
        val points: List<TutorVisualChartPointVariableProof>,
    ) : TutorVisualChartSeriesProof

    @Serializable
    @SerialName("derived_curve")
    data class DerivedCurve(
        val xStartVariableId: String,
        val xEndVariableId: String,
        val yDimension: TutorVisualDimension,
        /** Display unit for raw y coordinates; null is valid only for unitless values. */
        val yUnit: String? = null,
        val sampleCount: Int,
        val yDerivation: TutorVisualDerivation,
    ) : TutorVisualChartSeriesProof {
        init {
            xStartVariableId.requireTutorSceneId("Tutor visual curve start variable id")
            xEndVariableId.requireTutorSceneId("Tutor visual curve end variable id")
            yUnit?.requireSafeModelText(
                "Tutor visual curve y unit",
                TutorVisualVariable.MAX_UNIT_CHARS,
                false,
            )
            require(sampleCount in 2..TutorVisualDocumentScene.MAX_CHART_POINTS_PER_SERIES)
        }
    }

    @Serializable
    @SerialName("illustrative_trend")
    data class IllustrativeTrend(
        val purpose: TutorVisualIllustrativePurpose = TutorVisualIllustrativePurpose.TREND_SHAPE_ONLY,
    ) : TutorVisualChartSeriesProof {
        init {
            require(purpose == TutorVisualIllustrativePurpose.TREND_SHAPE_ONLY)
        }
    }
}

internal fun QuestionDocument.resolveTutorVisualSourceText(
    blockId: String,
    locator: TutorVisualSourceTextLocator,
): String {
    val block = blocks.singleOrNull { it.id == blockId }
        ?: throw IllegalArgumentException("Tutor visual source fact references an unknown block")
    return when (locator.field) {
        TutorVisualSourceTextField.PARAGRAPH_MARKDOWN ->
            (block as? ContentBlock.Paragraph)?.markdown
        TutorVisualSourceTextField.FORMULA_LATEX ->
            (block as? ContentBlock.Formula)?.latex
        TutorVisualSourceTextField.FORMULA_ALTERNATIVE_TEXT ->
            (block as? ContentBlock.Formula)?.alternativeText
        TutorVisualSourceTextField.CHOICE_PROMPT ->
            (block as? ContentBlock.ChoiceGroup)?.promptMarkdown
        TutorVisualSourceTextField.CHOICE_MARKDOWN ->
            (block as? ContentBlock.ChoiceGroup)?.choices?.getOrNull(locator.indices.single())?.markdown
        TutorVisualSourceTextField.FIGURE_TITLE ->
            (block as? ContentBlock.Figure)?.title
        TutorVisualSourceTextField.FIGURE_ALTERNATIVE_TEXT ->
            (block as? ContentBlock.Figure)?.alternativeText
        TutorVisualSourceTextField.SYMBOL_TABLE_HEADER ->
            ((block as? ContentBlock.Figure)?.schema as? FigureSchema.SymbolTable)
                ?.headers?.getOrNull(locator.indices.single())
        TutorVisualSourceTextField.SYMBOL_TABLE_CELL -> {
            val table = ((block as? ContentBlock.Figure)?.schema as? FigureSchema.SymbolTable)
            table?.rows?.getOrNull(locator.indices[0])?.getOrNull(locator.indices[1])
        }
        TutorVisualSourceTextField.CARTESIAN_X_AXIS_MINIMUM ->
            (((block as? ContentBlock.Figure)?.schema as? FigureSchema.Cartesian)?.xAxis?.minimum)?.toString()
        TutorVisualSourceTextField.CARTESIAN_X_AXIS_MAXIMUM ->
            (((block as? ContentBlock.Figure)?.schema as? FigureSchema.Cartesian)?.xAxis?.maximum)?.toString()
        TutorVisualSourceTextField.CARTESIAN_Y_AXIS_MINIMUM ->
            (((block as? ContentBlock.Figure)?.schema as? FigureSchema.Cartesian)?.yAxis?.minimum)?.toString()
        TutorVisualSourceTextField.CARTESIAN_Y_AXIS_MAXIMUM ->
            (((block as? ContentBlock.Figure)?.schema as? FigureSchema.Cartesian)?.yAxis?.maximum)?.toString()
        TutorVisualSourceTextField.CARTESIAN_POLYLINE_POINT_X,
        TutorVisualSourceTextField.CARTESIAN_POLYLINE_POINT_Y,
        -> {
            val chart = ((block as? ContentBlock.Figure)?.schema as? FigureSchema.Cartesian)
            val point = chart?.polylines?.getOrNull(locator.indices[0])
                ?.points?.getOrNull(locator.indices[1])
            when (locator.field) {
                TutorVisualSourceTextField.CARTESIAN_POLYLINE_POINT_X -> point?.x
                else -> point?.y
            }?.toString()
        }
        TutorVisualSourceTextField.CARTESIAN_POINT_X,
        TutorVisualSourceTextField.CARTESIAN_POINT_Y,
        -> {
            val chart = ((block as? ContentBlock.Figure)?.schema as? FigureSchema.Cartesian)
            val point = chart?.points?.getOrNull(locator.indices.single())?.coordinate
            when (locator.field) {
                TutorVisualSourceTextField.CARTESIAN_POINT_X -> point?.x
                else -> point?.y
            }?.toString()
        }
        TutorVisualSourceTextField.UNKNOWN_FALLBACK ->
            (block as? ContentBlock.Unknown)?.fallbackText
    } ?: throw IllegalArgumentException("Tutor visual source locator is incompatible with its block")
}

private fun String.containsTutorVisualNumericValue(expected: Double): Boolean =
    TUTOR_VISUAL_NUMERIC_LITERAL.findAll(this).any { match ->
        val candidate = match.value.parseTutorVisualNumericValue()
        candidate != null && tutorVisualNumbersEqual(candidate, expected)
    }

private fun String.parseTutorVisualNumericValue(): Double? {
    val normalized = replace(" ", "").replace('−', '-').replace('–', '-')
    return when {
        normalized == "π" || normalized == "+π" -> kotlin.math.PI
        normalized == "-π" -> -kotlin.math.PI
        '/' in normalized -> {
            val parts = normalized.split('/', limit = 2)
            val numerator = parts[0].toDoubleOrNull()
            val denominator = parts[1].toDoubleOrNull()
            if (numerator == null || denominator == null || kotlin.math.abs(denominator) < 1e-12) {
                null
            } else {
                numerator / denominator
            }
        }
        else -> normalized.toDoubleOrNull()
    }
}

fun tutorVisualNumbersEqual(left: Double, right: Double): Boolean {
    if (!left.isFinite() || !right.isFinite()) return false
    val tolerance = maxOf(1e-9, maxOf(kotlin.math.abs(left), kotlin.math.abs(right)) * 1e-9)
    return kotlin.math.abs(left - right) <= tolerance
}

private fun NormalizedSourceRegion.contains(other: NormalizedSourceRegion): Boolean =
    other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

private fun NormalizedSourceRegion.isValidTutorVisualRegion(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
        left in 0.0..1.0 && top in 0.0..1.0 && right in 0.0..1.0 && bottom in 0.0..1.0 &&
        left < right && top < bottom

private fun String.isLowercaseSha256(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private val TUTOR_VISUAL_NUMERIC_LITERAL = Regex(
    pattern = "(?<![A-Za-z_])[-+−–]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)(?:[eE][-+]?\\d+)?(?:\\s*/\\s*[-+−–]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))?|[-+−–]?π",
)
