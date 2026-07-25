package com.tingyun.smartmistakebook.quality.visualbenchmark

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private val strictJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}

fun main(args: Array<String>) {
    val exitCode = runCatching {
        when (args.firstOrNull()) {
            "validate" -> validateDataset(args.drop(1))
            "score" -> scoreProviders(args.drop(1))
            else -> error(usage())
        }
    }.fold(
        onSuccess = { 0 },
        onFailure = { error ->
            System.err.println(error.message)
            1
        },
    )
    if (exitCode != 0) exitProcess(exitCode)
}

private fun validateDataset(args: List<String>) {
    require(args.size in 1..2) { usage() }
    val manifestPath = Path.of(args[0])
    val datasetRoot = args.getOrNull(1)?.let(Path::of)
    val manifest = readManifest(manifestPath)
    val report = VisualBenchmarkDatasetValidator.validate(
        manifest = manifest,
        requirements = VisualBenchmarkRequirements.RELEASE,
        datasetRoot = datasetRoot,
    )
    report.requireValid()
    println(
        "Dataset ${manifest.datasetId}@${manifest.datasetVersion}: " +
            "${manifest.cases.size} cases, " +
            "${manifest.cases.map { it.subject }.distinct().size} subjects, " +
            "${manifest.cases.map { it.family }.distinct().size} figure families.",
    )
}

private fun scoreProviders(args: List<String>) {
    require(args.size == 2) { usage() }
    val manifest = readManifest(Path.of(args[0]))
    VisualBenchmarkDatasetValidator.validate(manifest).requireValid()
    val runs = strictJson.decodeFromString<VisualBenchmarkProviderRuns>(
        Files.readString(Path.of(args[1])),
    )
    val gate = VisualProviderBenchmarkScorer.evaluate(manifest, runs.runs)
    gate.providerReports.forEach { report ->
        println(
            "${report.providerId}/${report.modelVersion}: " +
                "first=${"%.2f".format(report.firstPassRate * 100)}%, " +
                "after_repair=${"%.2f".format(report.passAfterAtMostOneRepairRate * 100)}%, " +
                "safe=${"%.2f".format(report.safeHandlingRate * 100)}%",
        )
    }
    gate.requirePassed()
}

private fun readManifest(path: Path): VisualBenchmarkManifest =
    strictJson.decodeFromString(Files.readString(path))

private fun usage(): String =
    """
    Usage:
      validate <manifest.json> [private-dataset-root]
      score <manifest.json> <provider-runs.json>
    """.trimIndent()
