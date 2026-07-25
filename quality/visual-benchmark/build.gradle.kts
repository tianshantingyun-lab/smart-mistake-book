import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.tingyun.smartmistakebook.quality.visualbenchmark.MainKt")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:visual-runtime"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
