plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "com.tingyun.smartmistakebook"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    signingConfigs {
        create("release") {
            // Placeholder — populated by environment variables at build time.
            // Do NOT check real keystore files into source control.
        }
    }

    defaultConfig {
        applicationId = "com.tingyun.smartmistakebook"
        minSdk = 23
        targetSdk = 36
        // RELEASE.md gate 7: bumped for the first post-audit build. The stale
        // "phase0" suffix is gone (scenario-registry: old Phase labels are no
        // longer acceptance labels).
        versionCode = 2
        versionName = "0.2.0"

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
        create("benchmark") {
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isProfileable = true
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
        }
        create("internal") {
            matchingFallbacks += listOf("release")
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
        }
        release {
            // Signing is injected at evaluation time by configureReleaseSigningIfAvailable;
            // the validateReleaseSigning task fails the build before release packaging
            // if any signing environment variable is missing.
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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

// Populates the release signing config at evaluation time; silently skips when env vars are absent.
fun configureReleaseSigningIfAvailable() {
    val releaseKeyAlias = System.getenv("RELEASE_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("RELEASE_KEY_PASSWORD")
    val releaseStoreFile = System.getenv("RELEASE_STORE_FILE")
    val releaseStorePassword = System.getenv("RELEASE_STORE_PASSWORD")
    if (releaseKeyAlias != null && releaseKeyPassword != null &&
        releaseStoreFile != null && releaseStorePassword != null
    ) {
        android.signingConfigs.getByName("release").apply {
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            storeFile = file(releaseStoreFile)
            storePassword = releaseStorePassword
        }
    }
}

// 求值期注入签名，缺环境变量时静默跳过。
configureReleaseSigningIfAvailable()

tasks.register("validateReleaseSigning") {
    doLast {
        val missing = buildList {
            if (System.getenv("RELEASE_KEY_ALIAS").isNullOrBlank()) add("RELEASE_KEY_ALIAS")
            if (System.getenv("RELEASE_KEY_PASSWORD").isNullOrBlank()) add("RELEASE_KEY_PASSWORD")
            if (System.getenv("RELEASE_STORE_FILE").isNullOrBlank()) add("RELEASE_STORE_FILE")
            if (System.getenv("RELEASE_STORE_PASSWORD").isNullOrBlank()) add("RELEASE_STORE_PASSWORD")
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing is missing required environment variables: ${missing.joinToString(", ")}",
            )
        }
    }
}

// Apply release signing before release bundle/assemble tasks run.
tasks.configureEach {
    if (name.contains("Release", ignoreCase = true) &&
        (name.startsWith("bundle") || name.startsWith("assemble") || name.startsWith("package"))
    ) {
        dependsOn("validateReleaseSigning")
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:ui"))
implementation(project(":core:visual-ui"))
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
    implementation(libs.androidx.work.runtime.ktx)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.junit)
}
