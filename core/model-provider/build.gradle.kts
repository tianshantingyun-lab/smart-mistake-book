import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.artifacts.ProjectDependency

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
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

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    testFixturesImplementation(project(":core:model"))
    testFixturesImplementation(project(":core:domain"))
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testImplementation(testFixtures(project(":core:model")))
    testImplementation(libs.junit)
}

val permittedProjectDependencies = setOf(":core:model", ":core:domain")
configurations.configureEach {
    dependencies.withType(ProjectDependency::class.java).configureEach {
        check(path == project.path || path in permittedProjectDependencies) {
            "The external model provider cannot depend on trusted storage module " +
                path
        }
    }
}
