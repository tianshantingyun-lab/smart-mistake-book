plugins {
    id("com.android.test")
}

configurations.configureEach {
    resolutionStrategy.force(
        "androidx.tracing:tracing-perfetto:1.0.0",
        "androidx.tracing:tracing-perfetto-binary:1.0.0",
        "androidx.tracing:tracing-perfetto-common:1.0.0",
        "androidx.tracing:tracing-perfetto-handshake:1.0.0",
    )
}

android {
    namespace = "com.tingyun.smartmistakebook.macrobenchmark"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 23
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["androidx.benchmark.fullTracing.enable"] = "true"
        missingDimensionStrategy("networkMode", "strictOffline")
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.tracing.perfetto)
    implementation(libs.androidx.tracing.perfetto.binary)
}
