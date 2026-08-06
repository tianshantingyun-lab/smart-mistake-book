# Android 工作区环境

Android SDK、模拟器、Gradle、缓存、AVD、日志和临时文件均存放在 `D:\智能错题本` 下。

脚本会创建 `android-env.ps1` 中声明的 ASCII 运行时别名并验证它仍指向该工作区，用于规避 Windows QEMU 对非 ASCII 可执行路径的编码问题；它不会把工程数据存到其他磁盘。

`android-env.ps1` 是 Android 工具配置的单一事实源：AVD 名称、编译 SDK、Build Tools、运行镜像 API、默认模拟器端口、独立 ADB 服务端口，以及 serial/endpoint 的派生规则都只在那里声明。`verify-android-env.ps1` 会分别验证工程编译所需的平台与 Build Tools，以及模拟器运行所需的系统镜像和 AVD，避免把编译 API 与运行 API 混为一谈。

请使用以下 PowerShell 入口。脚本会使用独立的 ADB 服务端口；用户目录没有既有 Android 文件时，本次意外生成的文件会被归入工作区，有既有文件时则在运行前后做完整快照核对：

```powershell
& .\tools\verify-android-env.ps1
& .\tools\protect-android-workspace.ps1 -ValidateOnly
& .\tools\run-gradle.ps1 :app:assembleStrictOfflineDebug test
& .\tools\start-emulator.ps1
& .\tools\wait-for-emulator.ps1
& .\tools\stop-emulator.ps1
```

所有 Gradle 任务都应通过 `run-gradle.ps1` 运行。它固定使用环境脚本验证过的工作区别名、D 盘系统 Gradle、`--no-daemon` 和 D 盘缓存，并直接调用已通过 SHA-256 校验的 `gradle.bat` 绝对路径，避免 PowerShell 函数或别名劫持；同时把 `user.home` 显式传给 Gradle 客户端和实际构建守护进程，保证 AGP 的调试签名文件也落在工作区。设备、安装和 connected 测试必须显式传入 `-EmulatorPort`，只连接工作区独立的 ADB 服务端口。

如果 Windows 用户目录中原本已有 `.android` 或模拟器控制台令牌，脚本不会移动、删除或覆盖这些既有数据，而会在设备任务前后对目录结构、内容、长度和时间戳做快照核对；发生任何变化即令任务失败。工作区写入路径只要包含指向外部位置的重解析点，脚本同样会拒绝运行。

`start-emulator.ps1` 会等待模拟器完成 Windows 侧的延迟 ADB 初始化，并确认连续 15 秒无用户目录残留后再返回；普通启动最多等待 90 秒，`-WipeData` 初始化最多等待 180 秒。运行期间若检测到不属于该工作区/指定模拟器的 ADB 或模拟器进程，脚本会安全退出。

`protect-android-workspace.ps1 -ValidateOnly` 会校验工作区根目录、`tools`、`.toolchains`、`.android` 的受保护 ACL，以及 ADB 身份和关键 Android/Gradle 可执行文件的固定 SHA-256。主动升级 SDK、模拟器或 Gradle 后必须先审查来源，再同步更新这些固定哈希。

需要可交互的模拟器窗口时，为 `start-emulator.ps1` 添加 `-ShowWindow`；需要清空设备数据时添加 `-WipeData`。

## 知识内容覆盖审计

生产代码通用性门禁独立于具体题集运行。它拒绝在 `src/main` 中按固定题目 ID、题目修订、图片指纹、测试名、夹具名或基准样本 ID 分支；正常的变量对变量身份校验和结构化协议分支不受影响：

```powershell
& '<bundled-document-python>' .\tools\audit_generalization.py
& '<bundled-document-python>' .\tools\audit_database_boundaries.py
```

第二项门禁固定学生错题集、分科学习掌握库和九科知识库的物理边界：先验证三个不同的数据库文件名和三个独立 Room 容器，再禁止模块直接依赖或导入彼此的数据库实现。它扫描 `main`、`debug`、`release`、产品 flavor、`benchmark` 等所有会进入可安装构建的源码集，只排除 `test`、`androidTest`、`testFixtures` 及其变体。错题库与掌握库各只允许 `core:data` 中一个明确登记的 Java owner-access 文件使用同包桥；任何其他 split-package 类都会失败。跨库协作只能通过共享协议和由本地运行时装配的受控能力完成。

源码审计后可把每个可安装模块的实际编译目录（包括 generated 源码产生的 class）、单个 `.class`、JAR 或 AAR 交给同一脚本做字节码门禁；参数可以重复，CI 必须覆盖全部可安装模块。它会实读 classfile、JAR 以及 AAR 内的 `classes.jar`/嵌套 JAR，拒绝非 owner 产物中的数据库同包类，以及类和方法整体对包外可见时暴露的原始 relay、`CrossStoreEventEnvelope` 和 `access$` 合成入口。package-private 类的 public override 由全源码/产物同包来源门禁约束，不冒充跨包 API。唯一的公开原始消息例外是 owner 签发的 `public final *AuthenticityVerifier`：所有构造器都不可公开，唯一公开方法必须是接收精确 relay 类型并返回 `void` 的 `requireAuthentic`，且类中不能出现签名、证明或密码材料描述符。对外可见的方法描述符不得泄露 `SecretKey`、`Mac`、`KeyStore`；owner/store/`*Authenticator` 实现也不得公开签名、证明或取钥入口。普通的 `signedSummary` 一类业务命名不按敏感入口误报。例如：

```powershell
& '<bundled-document-python>' .\tools\audit_database_boundaries.py `
  --compiled-artifact .\core\student-mistake-database\build\intermediates\javac `
  --compiled-artifact .\core\learner-mastery-database\build\intermediates\built_in_kotlinc
```

该门禁用于约束受信任 APK 的源码与构建产物，不能也不声称阻止同 UID 恶意代码通过反射访问进程内对象；模型输出、远程数据和题面仍不得取得本地代码执行能力。

`extract-curriculum-coverage.py` 先核对九份 2025 课标 PDF 的字节数与 SHA-256，再按独立清单一次读取每份正文，提取模块和课程要求候选；输出始终是 `DRAFT_UNREVIEWED`，不会把机械分段冒充人工审校结果。`build-curriculum-warning-evidence.py` 为机械提取不可靠的模块保存完整原文证据，`audit-curriculum-warning-proposals.py` 要求每项细分建议都能回指原文，`audit-curriculum-warning-promotion.py` 则保证未完成逐项人工审校时不能生成正式范围。`audit-knowledge-source-register.ps1` 校验机器可读来源登记，区分待取得、仅元数据已核对、已取得未审校和已取得已审校；`audit-open-teaching-epubs.py` 只检查已取得开放教材的哈希、EPUB 结构、版权或许可文档中的授权 URI，以及每本资料自行声明的教学结构证据，不会把理科的 worked example/solution 词形硬套给语言和人文学科，也不会据此宣称已经完成中国课标映射。其余覆盖与内容包审计继续防止样例或草稿自证完整：

```powershell
& '<bundled-document-python>' .\tools\extract-curriculum-coverage.py --check
& '<bundled-document-python>' .\tools\build-curriculum-warning-evidence.py --check
& '<bundled-document-python>' .\tools\audit-curriculum-warning-proposals.py
& '<bundled-document-python>' .\tools\audit-curriculum-warning-promotion.py
& '<bundled-document-python>' .\tools\audit-open-teaching-epubs.py --check
# 在 worktree 中复用仓库外的私有资料；清单仍从当前 worktree 读取
& '<bundled-document-python>' .\tools\audit-open-teaching-epubs.py --check --artifact-root 'D:\智能错题本'
& '<bundled-document-python>' .\tools\build-open-teaching-review-inventory.py --check
# 复杂题图候选只写定位、哈希、通用图像指标和待审版权证据；输出必须在 .artifacts 或仓库外
# 固定运行时：CPython 3.12.13；Python 依赖见 requirements-visual-candidate.lock（Pillow 12.2.0）。
# 工具会在扫描前核对这两个精确版本，不匹配即失败关闭。
# `<bundled-document-python>` 是占位符，不是字面命令。该解释器由 Codex 的文档运行时提供，
# 不要用系统 python 顶替：本机系统 Python 版本/依赖不同会让这些工具成批误报。
# 在 Codex 桌面会话中通过文档依赖加载能力解析实际路径；命令行用户按本地工具链文档固定该别名。
& '<bundled-document-python>' -c "import platform, PIL; print(platform.python_implementation(), platform.python_version(), PIL.__version__)"
& '<bundled-document-python>' .\tools\build-open-visual-candidate-inventory.py `
  --artifact-root 'D:\智能错题本' `
  --output '.artifacts\open-visual-candidates.json' `
  --summary-output '.artifacts\open-visual-candidates-summary.json' `
  --max-candidates 120 --max-per-source 24 --write
# 确定性核对已有清单时可使用同样参数并把 --write 改为 --check
# stdout 摘要包含 manifest/source-register/inventory SHA-256、非空候选数、逐来源数量、
# 完整隔离预算统计和固定运行时；真实 120 条扫描以该脱敏摘要作为门禁，不提交图片或正文。
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

正式内容制作输入与私有人工验收留出集使用独立登记。`compile-formal-knowledge-pack.py --check/--write` 必须同时取得五项内容输入的版本化 HMAC 清单、经过 RSA 签名的非空留出指纹清单、完整指纹已由编译器钉住的发布登记和仓库外 HMAC 密钥；调用者自带公钥、同名换钥、旧发布代次、缺失、伪造、过期、撤销、清单滞后或任一重叠都会失败关闭。扫描覆盖完整值、嵌入值、跨字段且夹有普通值的保序分片、规范化文本、派生文本、带类型域和边界的短数值/符号、带前缀图片摘要、JSON 化结构化批注、稳定验收编号和派生别名，不包含题号规则或某道题的专用例外。所有输入会在深拷贝、HMAC 和滑动窗口前完成 schema 与深度、节点、字节、字符串数量预算检查，超限失败关闭。真实留出图片、提示和标注不得写入仓库；正式产物只记录清单与登记版本、整体指纹、数量和零重叠结论，不记录逐条指纹。

正式包编译完成后，`generate-formal-knowledge-release.py` 执行第二阶段的确定性转换和离线签名。它除 schema v2 正式产物、独立审核的 release governance JSON 和仓库外 RSA 私钥外，还必须取得原始五项编译输入、同一套留出证明，以及外部钉住的正式签名登记；登记精确绑定签名密钥编号、公钥指纹和发布代次。调用者用自己的私钥和自建登记不能替换信任根。签名前会重新验签、核对两项钉住登记并重跑重叠检测；产物自报的合规摘要不能替代这次验证。正式 Kotlin 渲染器不接受普通字典或调用者密钥，只消费安全签名流程内部签发的一次性授权；公开直接渲染入口始终拒绝。生成后会再次核对固定登记、签名回执以及两个 Kotlin 文件的 SHA-256，CLI 写盘后也会读取文件复核。私钥口令只从 `SMART_MISTAKEBOOK_KNOWLEDGE_SIGNING_KEY_PASSWORD` 读取，私钥字节、HMAC 密钥和口令不会进入生成物或日志。具体字段、命令和失败关闭规则见 `core/knowledge-database/FORMAL_KNOWLEDGE_PACK_PIPELINE.md`。
