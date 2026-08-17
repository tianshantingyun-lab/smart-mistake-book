plugins {
    id("com.android.test")
}

android {
    namespace = "com.tingyun.smartmistakebook.benchmark"
    compileSdk = 37
    experimentalProperties["android.experimental.self-instrumenting"] = true

    defaultConfig {
        minSdk = 24
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        missingDimensionStrategy("networkMode", "localFirst")
    }

    targetProjectPath = ":app"

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
