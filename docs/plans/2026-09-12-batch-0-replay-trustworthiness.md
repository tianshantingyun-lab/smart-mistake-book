# 批 0「重放可信化」实现计划

> **决策依据**：`docs/audit-2026-09-12-kernel-readiness.md` §11 批 0、§12.4、§12.6，决策 D-1／D-4…D-15。
> **设计记录**：`docs/adr/0001`（归因写入时冻结）、`0002`（全量重放不可分块）、`0003`（重放地平线）。
> **词汇**：`CONTEXT.md`。

**分支**：`audit/kernel-readiness`，基线 `dc12065`。
**验证命令**（每条任务收尾都要跑；本机正规入口 `tools/run-gradle.ps1` 因 `.toolchains/` 缺失跑不动，
替代跑法与两条必要参数的理由见审计 §13.4）：

```powershell
$env:GRADLE_USER_HOME = "<worktree>\.gradle"     # 默认的 ~/.gradle 缺 app 的依赖
gradlew.bat :core:domain:test :core:data:testDebugUnitTest :app:testLocalFirstDebugUnitTest :app:assembleLocalFirstDebug --console=plain --no-daemon
```

**贯穿纪律**：新增的每条机制都要带一条证明它在生产路径上被消费的测试。本批任何一条修复，
若其断言可以通过「把被测逻辑删掉」而变绿，则该断言不算数——**每条都要做变异检查**。

---

## 任务 1　版本触发重放的判定可测【已完成】

用户已确认，见 §11 批 0 执行状态。产出：

- `StudyProjectionDrainer` 的构造参数由 `StudyDatabasePort` 收窄为 `LearningProjectionPort`（唯一产线改动，行为不变）。
- 新增 `core/data/src/test/.../study/StudyProjectionDrainerReplayTriggerTest.kt`（5 条用例）。
- 变异验证：恒 `false` → 3 红；`!=`→`==` → 5 红；比错字段 → 1 红；`&&`→`||` → 5 红。
- 回归：`:core:data:testDebugUnitTest` 390/390。

---

## 任务 2　重放与增量投影逐字段等价【已完成】

**定义域**（D-5）：**不含修正的账本**。修正结构上进不了增量路径，因此不属于「不等价」。

**文件**
- 修改：`core/domain/src/test/.../LearningProjectorTest.kt`（D-10：`full replay and incremental projection agree on the whole snapshot`，替换原先只比 `knowledgeMasteryStates` 的那条）
- 新建：`core/data/src/test/.../study/StudyProjectionDrainerEquivalenceTest.kt`（3 条用例）
- 新建：`core/data/src/test/.../study/LedgerProjectionFixture.kt`（端口替身与事件夹具，三个测试文件共用）
- 修改：`core/data/src/test/.../StudyProjectionDrainerReplayTriggerTest.kt`（改用共用替身，5 条用例不变）

**与计划的偏离（都已实测，逐条列出）**
1. 计划里的端口替身是内嵌在触发判定测试里的私有类；三个测试文件共用同一份替身，因此抽成
   `LedgerProjectionFixture.kt`。抽出的同时修了一处**夹具失真**：原替身自造
   `"fingerprint:${id}"`，而真实 DAO 写的是 `LearningLedgerFingerprint.event(...)`
   （`AttemptTransactionDao.kt:350`）。自造指纹会让记录表在两条路上天然不同，等价性测试
   将测不出真东西——现在替身用同一个规范化函数，并且真的维护呈现状态表（读取时按
   `toModel(checkpoint)` 的口径归位），因此 `presentationProjectionStates` 这一路也被覆盖。
2. 计划说「一次性全量」的切分用 `pageSize = 账本条数` 表示；替身的 `pageSize` 因此
   压过调用方传的 `limit`（生产里 drainer 传 100），这一点在替身的 KDoc 里写明了。
3. 计划的第 1 步（先让测试以「不等价」的方式失败一次）改用**变异**完成，见下：比临时改一行
   再撤掉更接近长期纪律，而且留下了可复核的记录。
4. 跨批封顶（D-11）用 4_097 条 `TutorAnswerExposureOutcome`，不是 4_097 条 `Attempt`：
   `Attempt` 会把 4_097 个观测累积进同一个知识点的 `independentCorrectObservations`，
   让每次投影都退化成 O(n²) 的全表过滤。`bounded*Records` 四张表是同一种形状，
   封顶口径由其中一张钉住即可；**四张表都被比较**——两条路的整份 `LearnerSnapshot`
   做 data class 相等，本身就覆盖四张表，只是超出上界的那张是曝光表。

**变异校验（每条断言都要能在它保护的生产逻辑被拿掉时变红）**

| 变异 | 结果 |
| --- | --- |
| `replay` 忽略曝光因果（`answerRevealSequence` 恒 `null`） | RED，**只**由等价性用例捕获 |
| `replay` 不施加修正（拿掉 `event.copy(...)`） | RED，只由修正升级用例捕获 |
| 记录表封顶保留最旧而非最新 | RED，只由封顶用例捕获 |
| 上界常量差一（`4_096` → `4_095`） | RED，只由封顶用例捕获 |
| `replay` 把新鲜度写成 `STALE` | RED，**只**由强化后的 domain 用例捕获 |
| `replay` 丢掉讲题曝光记录表 | RED，**只**由强化后的 domain 用例捕获 |
| `requiresReplay` 的版本子句恒 `false` | RED，只由版本不匹配那条捕获 |
| `requiresReplay` 把版本比较写成 `==` | RED，5 条全红 |
| `requiresReplay` 把 `&&` 写成 `\|\|` | RED，5 条全红 |

最后两行同时复验了**夹具重构没有削弱**触发判定测试的鉴别力。第一版试的两个变异
（`generatedAtEpochMillis` 不再推进）把整个 domain 测试类打红，说明它盖过了我要证明的东西——
因此换成了上表里那两条只打红新用例的变异。

**回归**：`:core:domain:test` 58 类 / 395 条、`:core:data:testDebugUnitTest` 50 类 / 394 条，
0 失败 0 错误。

**顺带取证的发现**：见下「任务 2 追加发现」。

---

## 任务 2 追加发现　N-06：排空步数上界被报成 CAS 冲突

**取证方式**（第一手，临时探针跑完即删）：6_401 条事件的账本 + 生产页大小 100。

```
PROBE: ledger events = 6401, production page size = 100, drain steps = 64
PROBE: threw com.tingyun.smartmistakebook.core.database.ProjectionCasConflictException:
       Projection did not drain within the bounded work limit
PROBE: commits before the throw = 64
PROBE: checkpoint after the throw = 6400
```

**事实**：`StudyProjectionDrainer.drain` 用 `repeat(MAX_PROJECTION_DRAIN_STEPS = 64)` 包住提交循环，
循环走完仍未追平就抛 `ProjectionCasConflictException`。64 页 × 100 条 = **6_400 条的积压**会让它触发；
此时 64 次提交**已经落库**（检查点已到 6_400），抛出的却是一个断言「检查点在排空途中变了」的异常——
而它在本次调用里根本没变过。

**后果**：`RoomBackedStudyExperienceRepository.runOperation` 捕获它、发一次失败事件、再抛给调用方。
用户看到一次"投影更新失败"，而 6_400 条其实已经投影成功；下一次调用从 6_400 接着走。
**唯一诚实的修法**是给它一个属于自己的异常（与任务 3 的 `ProjectionReplayLimitExceededException`
同族，但不属于"账本太大"而属于"一次排空的预算用完"），并且不谎报原因。

**不修的理由**：它属于「投影失败的出口」那一组（任务 5），而任务 5 之所以排在最后，
是因为出口修好之前，新异常只会落到一个说错原因的地方。**登记为批 1 第一条**，与 N-03 一起做。

---

## 任务 3　重放上界 ＋ 显式失败【已完成】

**文件**
- 修改：`core/database/src/main/.../StudyDatabasePort.kt`（D-13：新增 `ProjectionReplayLimitExceededException`，与 `LearningLedgerIntegrityException` 同处）
- 修改：`core/data/src/main/.../study/StudyProjectionDrainer.kt`（`commitFullReplay` 加检查；顶层 `internal const val MAX_FULL_REPLAY_EVENTS = 100_000`；构造器加上界参数）
- 修改：`core/data/src/test/.../study/StudyProjectionDrainerEquivalenceTest.kt`（2 条用例）

**落地形态**

- 检查点选在 `loadLearningLedger` 之后、`replay` **之前**——`replay` 一进去就在内存里持住整份账本，
  没有提前失败的机会。
- `MAX_FULL_REPLAY_EVENTS` 是**顶层** `internal const`，不是 companion 私有成员：
  取值口径（D-14）必须能被测试钉住，而仓里已有同形先例
  （`StudyDatabasePort.kt:32-33` 的两个容量常量就是顶层）。
- 构造器多了一个 `maxFullReplayEvents: Int = MAX_FULL_REPLAY_EVENTS`。
  **它消灭的具体失败**：上界的**边界行为**（超限即失败、旧检查点原样保留）若不做这个小口子，
  就要造十万条事件的夹具才测得到，于是这条上界会带着未验证的边界行为上线。生产永远用默认值。
- 异常不继承 `ProjectionCasConflictException`，因此 `drain` 的重试分支不会接住它。

**变异校验**

| 变异 | 结果 |
| --- | --- |
| 上界检查整个删掉 | **红**，只由上界用例捕获 |
| 上界差一（`>` → `>=`） | **红**，只由上界用例捕获 |
| 产线上界降到同族的 `4_096` | **红**，只由取值口径用例捕获 |
| 产线上界抬到 `100_000_000`（OOM 之后才触发） | **红**，只由取值口径用例捕获 |
| 让新异常继承 `ProjectionCasConflictException` | **无法构造**：`This type is final, so it cannot be extended.` |

最后一行是本次最值得记的结果：**D-13 的「不继承 CAS」目前由语言本身保证**——
`ProjectionCasConflictException` 是 final 类，想继承它根本编译不过。因此那条
`assertFalse(isAssignableFrom(...))` 不是因为"现在安全"才多余，它守的是**将来**：
N-06 要改的正是同一处类型层级，谁把它做成 `open`，这行会立刻红。

**回归**：`:core:domain:test` 395 条、`:core:data:testDebugUnitTest` 396 条，0 失败 0 错误。

---

## 任务 4　重放后不变量校验【**原方案被推翻**：四条不变量全已在计算点守住】

动手前逐条回读源码，结论是**清单上的每一条都已经是投影器的现成保证**，
一个"重放后校验器"在现有代码上没有可守的失败——按 §12.2，那就是多余机制，不实现。

| 原清单 | 一手核对结果 |
| --- | --- |
| `S` 有限且 `> 0` | `ForgettingCurve.kt:78-80` 的 `require(stabilityDays.isFinite() && stabilityDays > 0.0)` 在**每一次**用 S 算间隔时把关；`projectMemory` / `projectAnswerReveal` / `projectTutorAnswerExposure` 三条路都必经它。生产里不可能出现非正 S 的状态。 |
| 掌握度 `∈ [0,1]` | `LearningProjector.kt:588`、`:971` 更新后 `.coerceIn(0.0, 1.0)`；`:1099` 下界 `.coerceIn(0.0, probability)`。同样是计算点保证——而且 `coerceIn` 是**静默**的，事后校验连看都看不到（值已经被夹过了）。 |
| `nextReview` 不回退 | 对 FSRS 主干：`MemoryUpdateModel.kt:101-102` 先 `coerceAtLeast(1)` 天再加到 `effectiveAttemptAtEpochMillis`；毕业分支与曝光分支走 `reviewAtTargetRetention`（上表第一条已保证 S>0）。**每条路都至少 +1 天**，所以"回退"不可达。另外这条**对 `previous` 比较本身不成立**：版本升级或修正都会合法地把 `nextReview` 往前挪（那正是修复的目的），拿旧快照当基准会误报。 |
| 账本无 GAP | `replay` 自己 `require`「序列必须是 1..n 的连续唯一序列」（`:388`、`:391`、`:411`），而入口那侧 `commitFullReplay` 已经在 `status != COMPLETE` 时抛 `LearningLedgerIntegrityException`。 |

**因此这一项没有产出代码。** 这是 §12.2 那道门在起作用——不是"没做完"，是"按原样做就是错的"。

### 那么该做的是什么

上面这张表同时暴露了**真正的问题**，而且它不在"重放之后"，而在"重放之前"：

1. **投影器的 `require` 是"不可能分支"，但它们的失败没有人接。** `replay` 的三条 `require`
   都由 DAO 的写入侧保证（作答 id 幂等、呈现响应序号 CAS、一次终结性曝光），
   所以正常数据永远走不到；可一旦落到（部分恢复、导入了别的库、手工改过的库——
   本审计已经登记过 `restore-failure-closes-db-no-reopen` 与 `cleardata-fk-off-leak` 两条
   同类的数据完整性缺陷），抛出的是一个裸 `IllegalArgumentException`，全仓无人指名捕获。
   这正是 **N-04 的同一族**，处置属于任务 5，不属于一个输出校验器。
2. **`replay` 算了一份没人读的预测**（见下 N-07），而它恰恰在我们要设上界的那条路上分配内存。

**结论**：任务 4 与任务 5 合并——批 0 第 4 项的落点不是"校验重放结果"，而是"**让重放路径自己的
失败落到一个有名字的出口上**"（D-12 已经批准的方向）。这正是下面任务 5 要做的。

---

## 任务 4 追加发现　N-07：`replay` 在内存里造一份没人读的预测列表

**取证方式**（第一手）：全仓搜索 `LearningProjectionResult.predictions` 字段的读取方——**零命中**。

```
$ grep -rn "\.predictions" --include=*.kt core app feature | grep -v /build/     → 空
```

- `LearningProjector.replay` 在 `:557` 调 `generatePredictions(snapshot, ordered, projectedAt)`
  （`:1140`），对**每一条 Attempt × 每一个归因**构造一个 `StudentModelPrediction`，
  连同 `computeFeatureFingerprint` 的字符串拼接。
- 这些对象**只被塞进 `LearningProjectionResult.predictions`，没有任何读取方**；
  `commitProjection` 也没有承接它的参数。真在用的预测审计是另一条链
  （`RoomPredictionAuditSink` → `recordStudentModelPredictions`），与它无关。
- **后果**：它是在**重放**这条路上分配内存（O(作答数 × 归因数)，且随账本增长），
  而重放正是我们刚刚设上界的那条路——等于让上界要挡的内存峰值里多背了一块纯浪费。
  它同时让 `replay` 与 `project` 的结果在 `predictions` 上必然不同（一个非空、一个空），
  任何将来比较 `LearningProjectionResult` 的人都会看到一个假的差异。

**处置**：登记为 `P2`（不是 `P3`：它在被设上界的路径上分配内存），**推荐在批 4 删除整个
`generatePredictions` + `computeFeatureFingerprint` + 结果里那个字段**。删它是安全的
（零读取方），但动的是 `core:domain` 的公开类型，不属于批 0 的范围，因此只登记不动手。

---

## 任务 5　投影失败的出口（D-12：N-02／N-04／N-05）【已完成】

**这一条是「失败可见」那一半，不只是接线。** 现状三个出口都有毛病，必须先修出口，否则任务 3／4
抛出的异常只会落到一个**说错原因**的地方。

**文件**
- 新增：`app/src/main/.../StartupFailureMessages.kt`（四类失败 → 文案 + 诊断编号前缀）
- 新增：`app/src/main/.../StartupInitialization.kt`（两段初始化的**归属**，可单测）
- 修改：`app/src/main/.../SmartMistakeBookApplication.kt`（改为经 `runStartupInitialization`；删掉一处空操作赋值）
- 修改：`app/src/main/.../StartupState.kt`（`RecoverableFailure` 新增 `retryable`，默认 `true`）
- 新增：`app/src/test/.../StartupFailureMessagesTest.kt`（6 条）、`StartupInitializationTest.kt`（5 条）

**与计划的偏离**

1. **计划第 2 步说的「指名捕获点」落成了「归属函数」，不是「一个 catch 里做类型分派」。**
   理由：计划第 1 步自己就预感到「该映射不易单测」。只把文案抽成纯函数**不够**——
   **把两个 catch 合回去（也就是原缺陷本身）时，文案一个字都没变**，只写文案断言的测试会全绿。
   归属才是 N-02 的实质，所以 `runStartupInitialization(installKnowledgeBase, initializeProjection,
   logFailure)` 把两段分开执行、分别归类。变异 W1 证明这条判断是对的：把 catch 合回去，
   只有归属用例变红。
2. **计划第 3 步「`LearningLedgerIntegrityException` 与新异常都要有指名捕获点」完成。**
   但**不是**在投影器那三个裸 `require` 上逐个包（那是任务 4 被推翻时明确排除的做法）：
   三类失败在 `projectionFailure` 里按类型分派，前提「这个函数只在投影初始化的 catch 里被调用」
   写进了 KDoc——因为该异常全仓 26 处抛出点里**只有 2 处与学习账本有关**（登记为 N-09）。
3. **新增 `retryable`（计划没写）。** 属于 §12.1 的影响面闭合：横幅按 `isRetryable` 渲染
   「重试」按钮，而两类确定性失败（账本有缺口、超出重放上界）重试只会重放同一条错误——
   文案还正说「重试不会改变结果」。
4. **一处刻意行为改动（计划未预见，必须记）。** HEAD 上两句共用一个 `try`，`install()` 一抛就
   走不到 `initialize()`：**知识包安装失败会连带冻住学习进度**，而界面只说「自动分类会暂缓」。
   现在知识包失败后仍继续尝试投影。代价：两段都失败时只有投影那条会显示。
5. **删掉一处空操作**：`if (startupState.value is StartupState.Ready) { startupState.value = StartupState.Ready }`。
   `Ready` 是 `data object`，StateFlow 对相等值不发射，这行什么也没做（读起来却像个守卫）。
6. **计划第 4 步（N-01 登记为 P3）完成**：登记在审计 §12.5，未实现。
7. **计划第 5 步（N-03 移到批 1）完成**：仍在批 1，与 N-06 一起。
   另外**新登记 N-10**：`BundledKnowledgeBaseInstaller.installPack` 是三个独立事务，
   中途失败留下半装状态，下次启动的 `require` 再抛一次 → 永久卡死。本项**降低了它的危害面**
   （不再冻住学习进度），根因归批 1。

**变异校验（10 条，逐条点名命中的用例）**

| 变异 | 命中 |
|---|---|
| **W1 把两个 catch 合回去**（原缺陷本身） | **红**：`aProjectionFailureIsNeverBlamedOnTheKnowledgePack`、`bothFailuresAreLoggedSeparately…` |
| W2 取消被吞成失败态 | **红，只由** `cancellationIsNeverConvertedIntoAFailureState` |
| W3 知识包失败后跳过投影 | **红**：`aKnowledgeInstallFailureSaysSoAndDoesNotStopProjection`、`bothFailuresAreLoggedSeparately…` |
| W4 两段都失败时以知识包为准 | **红，只由** `bothFailuresAreLoggedSeparately…` |
| W5 两次失败塌成一条日志 | **红，只由** `bothFailuresAreLoggedSeparately…` |
| M1 投影文案加回「可以继续使用」 | **红，只由** `unknownProjectionFailureStillBlamesTheProjectionNotTheKnowledgePack` |
| M2 超出上界的文案不再说「重试不会改变结果」 | **红，只由** `replayLimitFailureDoesNotPromiseARetryWillHelp` |
| M3 账本与超出上界共用一个编号前缀 | **红**：`everyFailureClassCarriesItsOwnDiagnosticPrefix`、`ledgerIntegrityFailureNamesTheLedger…` |
| M4 账本损坏也提供重试 | **红，只由** `onlyTheUnknownProjectionFailureOffersARetry` |
| M5 `isRetryable` 忽略新字段 | **红，只由** `onlyTheUnknownProjectionFailureOffersARetry` |

W3 第一次写成了有歧义的 Kotlin（`if (…) attempt(…) else null` 后接换行的 `?.let`），
编译器报 `Return type mismatch: expected 'RecoverableFailure?', actual 'Any?'`——
**那是变异写坏了，不是"测试抓不到"**，已改成带花括号的写法后复跑。记在这里免得被读成一次漏网。

**验证**

```powershell
$env:GRADLE_USER_HOME = "<worktree>\.gradle"
gradlew.bat :app:testLocalFirstDebugUnitTest :app:testStrictOfflineDebugUnitTest :app:assembleLocalFirstDebug --console=plain --no-daemon
```

- `:app:testLocalFirstDebugUnitTest` —— 7 类 / 26 条 / 0 失败 / 0 错误
- `:app:testStrictOfflineDebugUnitTest` —— 8 类 / 27 条 / 0 失败 / 0 错误
- `:app:assembleLocalFirstDebug` —— `BUILD SUCCESSFUL`，APK 产出
- 回归：`:core:domain:test` 58 类 / 395 条；`:core:data:testDebugUnitTest` 50 类 / 396 条，全绿

（本机正规入口 `tools/run-gradle.ps1` 目前跑不动——`.toolchains/` 缺失；替代跑法与两条必要参数的
理由见审计文档 §13.4。）

**UNVERIFIED**：横幅的渲染本身没有自动化测试（全仓对 `startup_state_banner` /
`startup_retry_button` 零引用），因此「`retryable = false` 确实让按钮消失」只由 `isRetryable`
这一层支撑，**从状态到界面那一步未验证**；「知识包失败 + 投影成功」这一组合未在真机实测。

---

## 本批不做（已明确排除）

- **分块重放**——ADR-0002。
- **重放地平线**——ADR-0003 已立项，实现排在批 0 之后。
- **N-01**（死字段，`P3`）、**N-08**（`errorCategory` 只写不读，`P3`）、**N-09**（`LearningLedgerIntegrityException`
  被当通用异常用，`P3`）——只登记，见审计 §12.5。
- **N-07**（`replay` 造一份没人读的预测列表，`P2`）——推荐在批 4 删除
  `generatePredictions` + `computeFeatureFingerprint` + 结果字段；动的是 `core:domain` 公开类型，不属批 0。
- **N-03**（未包裹入口）、**N-06**（排空步数上界报成 CAS 冲突）、**N-10**（知识包半装后永久卡死）——批 1。
  N-03 与 N-06 同属「失败出口说错原因」，N-10 与 1.3 的 `ChatEvidenceDao` 幂等同属
  「写入侧的半成品状态在下次启动变成永久故障」；三者可共用同一套故障注入测试。
  **N-06 不能照抄第 3 项的做法**：第 3 项是「这次升级处理不了这么多条」，N-06 是「一次排空的预算
  用完、下次接着走」，后者是**可重试**的。
- 任何需要 `:core:database:connectedDebugAndroidTest` 或 `:app:connectedDebugAndroidTest` 的验证——
  本机不跑，一律标 `UNVERIFIED`（因此横幅渲染本身未验证，见任务 5）。
