plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    jacoco
}

android {
    namespace = "com.tingyun.smartmistakebook.core.data"
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
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildTypes {
        getByName("debug") {
            enableUnitTestCoverage = true
        }
    }
    sourceSets {
        getByName("androidTest") {
            assets.srcDir(rootProject.file("core/database/schemas"))
        }
    }
}

/**
 * 冻结金标集的 assets 副本**构建期同步**（测量设施）。
 *
 * `tools/kb_coverage/tables/golden_queries_v1.json`（+ `.sha256`）是题面的**唯一权威源**
 * （D12 判官冻结：不增不减不改，sha256 `7c004b76…`）。androidTest 读的是
 * `src/androidTest/assets/golden/` 下的副本——此前靠手工拷贝：源改了而副本忘了同步时，
 * 仪表化就在评一份过期题面，而**没有任何东西会报红**。这里把副本变成权威源的构建期重放：
 *
 * 1. 每次构建从权威源拷贝（幂等、可复算，人工不再参与）；
 * 2. 接在 androidTest assets 合并任务之前，使"跑 androidTest"必然带上最新副本；
 * 3. 运行时 sha256 断言（`GoldenRetrievalInstrumentedTest`）是第二道；
 * 4. 副本与权威源逐字节一致由 JVM 测试
 *    `GoldenRetrievalJvmTest.goldenAssetsMirrorMatchesRepoCopyByteForByte` 钉住（第三道）。
 *
 * 副作用说明：本任务写回**源码树内**的受版本控制文件（`git ls-files` 可见）。内容与权威源
 * 逐字节相同，所以正常构建不会让工作区变脏；副本被手工改过时，下一次构建即纠正。
 */
val goldenQuerySourceDir = rootProject.layout.projectDirectory.dir("tools/kb_coverage/tables")
val goldenQueryAssetDir = layout.projectDirectory.dir("src/androidTest/assets/golden")
val goldenQueryFileNames = listOf("golden_queries_v1.json", "golden_queries_v1.json.sha256")

// `include` 的具体名而不是整个目录：副本目录里只应有这两个文件，多拷/少拷都当场可见。
// 「源被改名/删除」这种情形不在这里额外设检查——`Copy` 静默少拷会被 JVM 漂移测试
// （`GoldenRetrievalJvmTest`）与运行时 sha 断言抓住，不叠第二套机制。
val syncGoldenQueryAssets = tasks.register<Copy>("syncGoldenQueryAssets") {
    group = "verification"
    description = "把冻结金标集（json + sha256 封存）从 tools/kb_coverage/tables 同步到 androidTest assets"
    from(goldenQuerySourceDir) {
        include(goldenQueryFileNames)
    }
    into(goldenQueryAssetDir)
}

// 按名字匹配而不是逐变体写死：AGP 9 的变体任务在脚本体之后才惰性注册，
// configureEach 才接得上（与本项目其它 AGP 9 适配同一原因）。
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets") }
    .configureEach { dependsOn(syncGoldenQueryAssets) }

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    // Stage-1 词面检索实验台（WP-C）：JVM 单测里跑**真** SQLite FTS5 + bm25() 的口径对照。
    // 只进测试运行时（testImplementation），不进 APK：生产侧仍走 Room/androidx.sqlite。
    testImplementation(libs.sqlite.jdbc)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.androidx.core.ktx)
}

/**
 * Unit-test coverage for an Android library (KD-5): Kover 0.9.1 cannot see
 * AGP 9's built-in-Kotlin build variants, so this module uses AGP's own
 * instrumentation (`enableUnitTestCoverage`) plus a Jacoco report with a
 * stable XML path that `tools/ci/generate_status.py` reads.
 */
tasks.register<JacocoReport>("unitTestCoverageXmlReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(false)
        csv.required.set(false)
        xml.outputLocation.set(layout.buildDirectory.file("reports/coverage/unit-test.xml"))
    }
    executionData.setFrom(
        layout.buildDirectory.file(
            "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
        ),
    )
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    // AGP 9 built-in Kotlin compiles to intermediates/built_in_kotlinc; the
    // javac output stays in intermediates/javac. Both are needed.
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug")) {
            include("**/classes/**")
            exclude("**/R.class", "**/BuildConfig.class", "**/*_Impl.class", "**/*_Factory.class")
        },
        fileTree(layout.buildDirectory.dir("intermediates/javac/debug/classes")) {
            exclude("**/R.class", "**/BuildConfig.class", "**/*_Impl.class", "**/*_Factory.class")
        },
    )
}
