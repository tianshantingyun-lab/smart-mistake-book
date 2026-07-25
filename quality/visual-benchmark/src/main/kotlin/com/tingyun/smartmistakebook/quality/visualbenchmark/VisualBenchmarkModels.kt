package com.tingyun.smartmistakebook.quality.visualbenchmark

import kotlinx.serialization.Serializable

const val VISUAL_BENCHMARK_SCHEMA_VERSION = 1

@Serializable
enum class VisualBenchmarkSubject {
    CHINESE,
    MATHEMATICS,
    ENGLISH,
    PHYSICS,
    CHEMISTRY,
    BIOLOGY,
    HISTORY,
    GEOGRAPHY,
    POLITICS,
    TECHNOLOGY,
}

@Serializable
enum class VisualFigureFamily {
    CRYSTAL_LATTICE,
    PARTITIONED_FLOW_DEVICE,
    ROTATING_FIELD_GEOMETRY,
    FLUID_COLUMN_SYSTEM,
    MULTI_SERIES_CHART,
    CIRCUIT,
    OPTICS,
    WAVEFORM,
    FORCE_AND_MECHANISM,
    SOLID_GEOMETRY,
    FUNCTION_AND_STATISTICS,
    MOLECULAR_STRUCTURE,
    EXPERIMENTAL_APPARATUS,
    BIOLOGICAL_STRUCTURE_AND_REGULATION,
    GEOGRAPHIC_SECTION_AND_PROCESS,
    MATERIAL_RELATIONSHIP,
    SYNCHRONIZED_MULTI_VIEW,
}

@Serializable
enum class VisualBenchmarkValueSource {
    GIVEN,
    DERIVED,
}

@Serializable
data class VisualBenchmarkManifest(
    val schemaVersion: Int = VISUAL_BENCHMARK_SCHEMA_VERSION,
    val datasetId: String,
    val datasetVersion: String,
    val cases: List<VisualBenchmarkCase>,
)

@Serializable
data class VisualBenchmarkCase(
    val caseId: String,
    val subject: VisualBenchmarkSubject,
    val family: VisualFigureFamily,
    val source: VisualBenchmarkImageSource,
    val currentQuestionPart: String,
    val annotations: VisualBenchmarkAnnotations,
)

@Serializable
data class VisualBenchmarkImageSource(
    val relativePath: String,
    val sha256: String,
    val widthPixels: Int,
    val heightPixels: Int,
)

@Serializable
data class VisualBenchmarkAnnotations(
    val objects: List<VisualBenchmarkObject>,
    val connections: List<VisualBenchmarkConnection> = emptyList(),
    val directions: List<VisualBenchmarkDirection> = emptyList(),
    val numericValues: List<VisualBenchmarkNumericValue> = emptyList(),
    val spatialRelations: List<VisualBenchmarkSpatialRelation> = emptyList(),
    val temporalOrder: List<VisualBenchmarkTemporalRelation> = emptyList(),
)

@Serializable
data class VisualBenchmarkObject(
    val semanticId: String,
    val label: String,
    val critical: Boolean = true,
)

@Serializable
data class VisualBenchmarkConnection(
    val fromSemanticId: String,
    val toSemanticId: String,
    val relation: String,
    val directed: Boolean = false,
)

@Serializable
data class VisualBenchmarkDirection(
    val semanticId: String,
    val fromSemanticId: String,
    val toSemanticId: String,
    val meaning: String,
)

@Serializable
data class VisualBenchmarkNumericValue(
    val semanticId: String,
    val canonicalValue: String,
    val unit: String,
    val source: VisualBenchmarkValueSource,
    val displayRequired: Boolean,
)

@Serializable
data class VisualBenchmarkSpatialRelation(
    val semanticId: String,
    val subjectSemanticId: String,
    val relation: String,
    val referenceSemanticId: String,
)

@Serializable
data class VisualBenchmarkTemporalRelation(
    val semanticId: String,
    val beforeState: String,
    val trigger: String,
    val afterState: String,
)

@Serializable
enum class VisualBenchmarkAttemptStage {
    INITIAL,
    REPAIR,
}

@Serializable
enum class VisualBenchmarkAttemptStatus {
    GENERATED,
    DECLINED_UNCERTAIN,
    FAILED,
}

@Serializable
data class VisualBenchmarkObservation(
    val objectSemanticIds: List<String>,
    val connections: List<VisualBenchmarkConnection> = emptyList(),
    val directions: List<VisualBenchmarkDirection> = emptyList(),
    val numericValues: List<VisualBenchmarkNumericValue> = emptyList(),
    val spatialRelations: List<VisualBenchmarkSpatialRelation> = emptyList(),
    val temporalOrder: List<VisualBenchmarkTemporalRelation> = emptyList(),
    val contradictions: List<String> = emptyList(),
)

@Serializable
data class VisualBenchmarkAttempt(
    val stage: VisualBenchmarkAttemptStage,
    val status: VisualBenchmarkAttemptStatus,
    val runtimeCompiled: Boolean,
    val visibleIllustrativeValue: Boolean,
    val observation: VisualBenchmarkObservation? = null,
    val failureReason: String? = null,
)

@Serializable
data class VisualBenchmarkCaseRun(
    val caseId: String,
    val attempts: List<VisualBenchmarkAttempt>,
)

@Serializable
data class VisualBenchmarkProviderRun(
    val providerId: String,
    val modelVersion: String,
    val cases: List<VisualBenchmarkCaseRun>,
)

@Serializable
data class VisualBenchmarkProviderRuns(
    val runs: List<VisualBenchmarkProviderRun>,
)
