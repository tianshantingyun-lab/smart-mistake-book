plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room3)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.tingyun.smartmistakebook.core.database"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core:model"))
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.room3.paging)
    implementation(libs.androidx.paging.common)
    implementation(libs.androidx.sqlite.framework)
    ksp(libs.androidx.room3.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.android)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}
