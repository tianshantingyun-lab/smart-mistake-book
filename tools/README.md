# Android 工作区环境

Android SDK、模拟器、Gradle、缓存、AVD、日志和临时文件均存放在 `D:\智能错题本` 下。

脚本会创建 `android-env.ps1` 中声明的 ASCII 运行时别名并验证它仍指向该工作区，用于规避 Windows QEMU 对非 ASCII 可执行路径的编码问题；它不会把工程数据存到其他磁盘。

`android-env.ps1` 是 Android 工具配置的单一事实源：AVD 名称、编译 SDK、Build Tools、运行镜像 API、默认模拟器端口、独立 ADB 服务端口，以及 serial/endpoint 的派生规则都只在那里声明。`verify-android-env.ps1` 会分别验证工程编译所需的平台与 Build Tools，以及模拟器运行所需的系统镜像和 AVD，避免把编译 API 与运行 API 混为一谈。

请使用以下 PowerShell 入口。脚本会使用独立的 ADB 服务端口，并在结束后把 Windows 用户目录中由本次运行产生的 Android 文件移回工作区：

```powershell
& .\tools\verify-android-env.ps1
& .\tools\protect-android-workspace.ps1 -ValidateOnly
& .\tools\run-gradle.ps1 :app:assembleStrictOfflineDebug test
& .\tools\start-emulator.ps1
& .\tools\wait-for-emulator.ps1
& .\tools\stop-emulator.ps1
```

所有 Gradle 任务都应通过 `run-gradle.ps1` 运行。它固定使用环境脚本验证过的工作区别名、D 盘系统 Gradle、`--no-daemon` 和 D 盘缓存，并直接调用已通过 SHA-256 校验的 `gradle.bat` 绝对路径，避免 PowerShell 函数或别名劫持；同时把 `user.home` 显式传给 Gradle 客户端和实际构建守护进程，保证 AGP 的调试签名文件也落在工作区。纯编译、Lint 和 JVM 测试不会使用 ADB 或模拟器，因此可以与用户原本已有的 Android 配置共存；设备、安装和 connected 测试仍必须显式传入 `-EmulatorPort`，并继续执行严格的用户目录隔离。

如果 Windows 用户目录中原本已有 `.android` 或模拟器控制台令牌，脚本会拒绝运行，不会移动或覆盖这些既有数据。工作区写入路径只要包含指向外部位置的重解析点，脚本同样会拒绝运行。

`start-emulator.ps1` 会等待模拟器完成 Windows 侧的延迟 ADB 初始化，并确认连续 15 秒无用户目录残留后再返回；普通启动最多等待 90 秒，`-WipeData` 初始化最多等待 180 秒。运行期间若检测到不属于该工作区/指定模拟器的 ADB 或模拟器进程，脚本会安全退出。

`protect-android-workspace.ps1 -ValidateOnly` 会校验工作区根目录、`tools`、`.toolchains`、`.android` 的受保护 ACL，以及 ADB 身份和关键 Android/Gradle 可执行文件的固定 SHA-256。主动升级 SDK、模拟器或 Gradle 后必须先审查来源，再同步更新这些固定哈希。

需要可交互的模拟器窗口时，为 `start-emulator.ps1` 添加 `-ShowWindow`；需要清空设备数据时添加 `-WipeData`。

## 知识内容覆盖审计

`extract-curriculum-coverage.py` 先核对九份 2025 课标 PDF 的字节数与 SHA-256，再按独立清单一次读取每份正文，提取模块和课程要求候选；输出始终是 `DRAFT_UNREVIEWED`，不会把机械分段冒充人工审校结果。`build-curriculum-warning-evidence.py` 为机械提取不可靠的模块保存完整原文证据，`audit-curriculum-warning-proposals.py` 要求每项细分建议都能回指原文，`audit-curriculum-warning-promotion.py` 则保证未完成逐项人工审校时不能生成正式范围。`audit-knowledge-source-register.ps1` 校验机器可读来源登记，区分待取得、仅元数据已核对、已取得未审校和已取得已审校；`audit-open-teaching-epubs.py` 只检查已取得开放教材的哈希、EPUB 结构、版权或许可文档中的授权 URI，以及每本资料自行声明的教学结构证据，不会把理科的 worked example/solution 词形硬套给语言和人文学科，也不会据此宣称已经完成中国课标映射。其余覆盖与内容包审计继续防止样例或草稿自证完整：

```powershell
& '<bundled-document-python>' .\tools\extract-curriculum-coverage.py --check
& '<bundled-document-python>' .\tools\build-curriculum-warning-evidence.py --check
& '<bundled-document-python>' .\tools\audit-curriculum-warning-proposals.py
& '<bundled-document-python>' .\tools\audit-curriculum-warning-promotion.py
& '<bundled-document-python>' .\tools\audit-open-teaching-epubs.py --check
& '<bundled-document-python>' .\tools\build-open-teaching-review-inventory.py --check
$env:PYTHONPATH = '.\tools'
& '<bundled-document-python>' -m unittest discover -s .\tools\tests -v
& .\tools\audit-curriculum-coverage-draft.ps1
& .\tools\audit-knowledge-source-rights.ps1
& .\tools\audit-knowledge-source-register.ps1
& .\tools\audit-knowledge-coverage-ledger.ps1
& .\tools\audit-knowledge-packs.ps1
& .\tools\audit-knowledge-packs.ps1 -RequireFullCoverage
```

这些命令分别验证机械提取可重放、警告模块证据完整、细分建议有出处、人工审校门没有被绕过，以及开放教学资料确实是登记过的许可版本。教学资料审校清单只保存章节定位、正文哈希和结构标记，不复制正文；它把可能的方法模型、典型例题、完整解答和推导送入人工审校，但初始决定集固定为 `NOT_STARTED`，不能自动修改正式知识包。只有来源生产门通过、独立范围清单九科审校完成、当前内容包与清单逐点一致、每个细化知识点都有教学支持绑定，而且九科各自具备多方方法模型、典型例题和完整解答，知识目录与教学支持才可声明为 `FULL`。现有 2020 样例、2025 解读目录、机械提取候选、待人工审校建议和已取得未审校的开放教材都不会被计入完整覆盖率。例题、完整解答和方法模型属于教学支持内容，不会因为含有题面或答案就被误判成题库；它们也不会取得出题、测评或复习调度权限。
