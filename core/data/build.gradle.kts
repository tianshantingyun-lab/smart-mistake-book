import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.provider.Provider

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

/**
 * LiteRT 2.1.6 的 manifest namespace 冲突（真机档实测暴露；库模块自己的合并不受影响，
 * 所以 JVM/库级门看不到它）。
 *
 * `com.google.ai.edge.litert:litert` 与 `...:litert-api` 两个 AAR **在各自清单里声明同一个
 * namespace `com.google.ai.edge.litert`**，AGP 9.3 的 app manifest 合并
 * （`ManifestMerger2#checkUniqueNamespaces`）把"两个库共用 namespace"判为错误：
 *
 * ```
 * Namespace 'com.google.ai.edge.litert' is used in multiple modules and/or libraries:
 * com.google.ai.edge.litert:litert:2.1.6, com.google.ai.edge.litert:litert-api:2.1.6.
 * ```
 *
 * 后果：`:app:assembleLocalFirstDebug` 在 `:app:processLocalFirstDebugMainManifest` 直接失败。
 * 两个 AAR 都必需——`litert` 给 `org.tensorflow.lite.Interpreter` 等 16 个类 + `libLiteRt.so`；
 * `litert-api` 给前者引用的 `InterpreterApi`/`Tensor`/`TensorFlowLite` 等类 + `liblitert_jni.so`
 * ——所以只能给其中一个换 namespace。`litert-api` 的 AAR 没有 `res/`（只有 classes.jar、
 * jni/、proguard.txt），改 namespace 不牵动资源或 R 类。
 *
 * 处置：把 litert-api 从 Android 依赖图里摘出来（`litert` 的那条传递依赖 exclude 掉），
 * 改为构建期从 Maven 原件派生一份"只改清单 namespace"的 AAR（其余条目逐字节保留，
 * 判据同 `tools/dense_build/patch_litert_api_aar.py`），再以本地 AAR 形式进 classpath。
 * 派生件写在 `build/` 下、**不入库**；上游坐标与两份 sha256 记在 `gradle/litert-patched/README.md`。
 * 上游修好后应整段删除（连同那四条显式传递依赖），回到单行 `implementation(libs.litert)`。
 */
val litertApiUpstream: Configuration = configurations.create("litertApiUpstream") {
    isCanBeConsumed = false
    isCanBeResolved = true
    // 只要那份 AAR 文件本身：传递依赖走主 classpath（下面四条显式声明 + 版本升级），
    // 免得把 ai-delivery → asset-delivery → work-runtime:2.9.1 这条老链拖进只取文件的配置。
    isTransitive = false
}

dependencies {
    litertApiUpstream("com.google.ai.edge.litert:litert-api:2.1.6")
}

abstract class PatchLitertApiAarTask : DefaultTask() {
    @get:InputFile
    abstract val upstreamAar: RegularFileProperty

    @get:OutputFile
    abstract val patchedAar: RegularFileProperty

    @TaskAction
    fun patch() {
        val source = upstreamAar.get().asFile
        val target = patchedAar.get().asFile
        target.parentFile.mkdirs()
        var manifestRewritten = false
        ZipFile(source).use { input ->
            ZipOutputStream(target.outputStream().buffered()).use { out ->
                for (entry in input.entries()) {
                    var bytes = input.getInputStream(entry).readBytes()
                    if (entry.name == "AndroidManifest.xml") {
                        val manifest = bytes.toString(Charsets.UTF_8)
                        val original = "package=\"com.google.ai.edge.litert\""
                        check(manifest.contains(original)) {
                            "litert-api 清单里没有 $original（上游可能已改：本段应连同注释整体删除）"
                        }
                        bytes = manifest.replace(original, "package=\"com.google.ai.edge.litert.api\"")
                            .toByteArray(Charsets.UTF_8)
                        manifestRewritten = true
                    }
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        check(manifestRewritten) { "litert-api AAR 里没有 AndroidManifest.xml" }
        logger.lifecycle("patched litert-api AAR -> " + target.absolutePath)
    }
}

val patchLitertApiAar = tasks.register<PatchLitertApiAarTask>("patchLitertApiAar") {
    group = "build"
    description = "派生一份只改清单 namespace 的 litert-api AAR（AGP 9 manifest 合并要求库 namespace 唯一）"
    val upstreamAarFile: Provider<File> = litertApiUpstream.incoming
        .artifactView { isLenient = false }
        .artifacts.resolvedArtifacts
        .map { artifacts: Set<ResolvedArtifactResult> ->
            artifacts.first { it.file.name.endsWith(".aar") }.file
        }
    upstreamAar.set(layout.file(upstreamAarFile))
    patchedAar.set(layout.buildDirectory.file("litert-patched/litert-api-2.1.6-patched.aar"))
}

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
    // Stage-3 端侧稠密腿（bge-small-zh-v1.5 int8 + LiteRT）：**只有 core:data 用它**，
    // 且只在 Android 运行时（JVM 单测不依赖：模型推理留仪表化，见 knowledge/dense/DenseQueryEncoder.kt）。
    // litert-api 被摘出来改走上面派生的本地 AAR（namespace 冲突，见 patchLitertApiAar 的注释）；
    // 它的四条 POM 传递依赖随之显式补回，classpath 与上游 POM 等价。
    implementation(libs.litert) {
        exclude(group = "com.google.ai.edge.litert", module = "litert-api")
    }
    implementation(files(patchLitertApiAar))
    implementation(libs.litert.api.lifecycle.runtime)
    implementation(libs.litert.api.guava)
    implementation(libs.litert.api.coroutines.guava)
    implementation(libs.litert.api.play.delivery)
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
