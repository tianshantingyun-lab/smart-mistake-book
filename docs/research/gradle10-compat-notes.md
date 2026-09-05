# Gradle 10 兼容性调研（P2-10 收口）

- 调查日期：2026-09-06
- 现状：Gradle 9.6.1（`gradle/wrapper/gradle-wrapper.properties`），构建输出有
  "Deprecated Gradle features … incompatible with Gradle 10" 汇总警告。

## 结论：警告来自 Kotlin Gradle Plugin 内部，不是本项目脚本

- 定位方法：`./gradlew :core:domain:compileDebugKotlin --offline --warning-mode all`。
- 警告原文：`Using a Project object as a dependency notation has been deprecated …
  Please use the project(String) method on DependencyHandler …`，出现在
  `Configure project :core:domain` / `:core:model`（所有 `kotlin("jvm")` 模块）。
- 排除过程：
  - `core/domain/build.gradle.kts` 与根脚本均为规范写法（`implementation(project(":core:model"))`）；
  - 无 buildSrc、无 convention 插件；
  - 未应用 Kover 的 `:core:model` 同样复现 → 与 Kover 无关；
  - 共同因素 = `org.jetbrains.kotlin.jvm` 插件（KGP 2.3.21 在配置 JVM 模块时向
    `implementation` 注入 stdlib 使用了 Project 对象记法）。
- Android 库/应用模块（AGP 侧）未复现该警告。

## 处置

1. **不修**：项目侧无可改之处；等 KGP 上游修复后随 Kotlin 版本升级消除。
2. 升级 Kotlin/AGP 时（当前 kotlin=2.3.21 / agp=9.3.0 / ksp=2.3.10），
   用 `--warning-mode all` 复跑本文件的两条验证命令确认警告消失。
3. 届时顺带核对 `android.overridePathCheck=true` 与
   `BuildType 'internal' is both debuggable and has 'isMinifyEnabled' set to true`
   两条配置期警告（后者表明 internal 变体的 R8 实际未生效，属已知有意配置）。

## 复验命令

```bash
./gradlew :core:domain:compileKotlin --offline --warning-mode all --console=plain \
  | grep -c "Project object as a dependency"   # 期望随 KGP 升级降为 0
```
