plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

configurations.configureEach {
    if (name.contains("Benchmark", ignoreCase = true)) {
        resolutionStrategy.force(
            "androidx.tracing:tracing-perfetto:1.0.0",
            "androidx.tracing:tracing-perfetto-binary:1.0.0",
            "androidx.tracing:tracing-perfetto-common:1.0.0",
            "androidx.tracing:tracing-perfetto-handshake:1.0.0",
        )
    }
}

android {
    namespace = "com.tingyun.smartmistakebook"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.tingyun.smartmistakebook"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-phase0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    flavorDimensions += "networkMode"
    productFlavors {
        create("strictOffline") {
            dimension = "networkMode"
            applicationIdSuffix = ".offline"
            versionNameSuffix = "-offline"
        }
        create("localFirst") {
            dimension = "networkMode"
            applicationIdSuffix = ".localfirst"
            versionNameSuffix = "-localfirst"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:ui"))
    implementation(project(":feature:capture"))
    implementation(project(":feature:review"))
    implementation(project(":feature:tutor"))
    implementation(project(":feature:library"))
    implementation(project(":feature:profile"))

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.profileinstaller)

    add("benchmarkImplementation", platform(libs.compose.bom))
    add("benchmarkImplementation", libs.compose.runtime.tracing)
    add("benchmarkImplementation", libs.androidx.tracing.perfetto)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.junit)
}
