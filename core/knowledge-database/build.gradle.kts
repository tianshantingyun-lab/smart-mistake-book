plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room3)
}

val runKnowledgeScaleBenchmark =
    providers.gradleProperty("runKnowledgeScaleBenchmark")
        .map { value -> value.toBooleanStrict() }
        .orElse(false)
val knowledgeScaleBenchmarkClass =
    "com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeRetrievalScaleInstrumentedTest"

android {
    namespace = "com.tingyun.smartmistakebook.core.knowledge.database"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["runKnowledgeScaleBenchmark"] =
            runKnowledgeScaleBenchmark.get().toString()
        if (runKnowledgeScaleBenchmark.get()) {
            testInstrumentationRunnerArguments["class"] = knowledgeScaleBenchmarkClass
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.sqlite.framework)
    ksp(libs.androidx.room3.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.android)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

val requireKnowledgeScaleBenchmark =
    tasks.register("requireKnowledgeScaleBenchmark") {
        group = "verification"
        description = "Rejects accidental or silent execution of the full knowledge scale gate."
        doLast {
            check(runKnowledgeScaleBenchmark.get()) {
                "Run with -PrunKnowledgeScaleBenchmark=true to execute the 50k/1m/250k scale gate."
            }
        }
    }

tasks.register("connectedKnowledgeScaleBenchmark") {
    group = "verification"
    description =
        "Runs only the explicit 50k-node/1m-feature/250k-relation instrumented release gate."
    dependsOn(requireKnowledgeScaleBenchmark, "connectedDebugAndroidTest")
}

tasks.matching { task -> task.name == "connectedDebugAndroidTest" }.configureEach {
    mustRunAfter(requireKnowledgeScaleBenchmark)
    if (runKnowledgeScaleBenchmark.get()) {
        outputs.upToDateWhen { false }
    }
}
