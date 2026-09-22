import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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

/**
 * 档1 门禁统一按 Android 风格任务名 `testDebugUnitTest` 点名各模块单测；纯 JVM 模块
 * 只有 `test`，注册同名任务委托给它，使命令可解析（实际运行的测试与 `test` 完全相同）。
 */
tasks.register("testDebugUnitTest") {
    dependsOn(tasks.named("test"))
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
