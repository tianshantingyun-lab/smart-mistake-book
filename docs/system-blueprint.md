# 智能错题本完整系统蓝图

版本：0.8（2026-07-22 产品合同覆盖；实现状态单独追踪）  
日期：2026-07-22  
适用范围：面向中国高中生、由多模态大模型驱动且由 Android 端保存可信记忆的学习应用

治理入口：产品的本地/模型职责以 [`model-first-product-boundaries.md`](model-first-product-boundaries.md) 为权威，界面、用户习惯与功能完整性以 [`student-experience-spec.md`](student-experience-spec.md) 为权威，当前实现与证据状态以 [`scenario-registry.md`](scenario-registry.md) 为权威。本文保留体系架构与长期风险参考；[`m1-exhaustive-product-contract.md`](m1-exhaustive-product-contract.md) 继续约束历史 M1–M4 门禁，但其中“纯本地优先”不再决定当前产品优先级。本文场景表中的 `CAP-*`、`TUT-*`、`REV-*`、`LIB-*`、`ME-*` 是历史局部短 ID；跨文档引用必须加 `BP-`（例如 `BP-CAP-01`），不得与 M1 合同的 `M1-*` 混用。

> **2026-07-22 产品合同覆盖：** 本文后续的旧场景、算法、页面和里程碑示例只在与下方冻结执行规则一致时可实施；相反行为只算历史风险样本，不能进入需求、实现或验收。

### 冻结执行规则（实现与验收必须逐条满足）

| 边界 | 唯一允许的当前行为 | 明确禁止 |
|---|---|---|
| 导航 | 底栏固定为 `复习 / 讲题 / 错题本 / 我的`；拍照是带 `teach/save` 意图的共享入口 | 第五根页、全局相机 FAB、把打卡拆成根页 |
| 图片转文档 | 模型判断内容是否足够并生成结构化转写；本地只做安全解码、资产校验、Schema 与提交。正常候选直接继续；失败只给重试、重拍/补拍、换图、稍后继续并保留草稿 | 强制 OCR 校对、逐字段确认、识别失败后要求学生手工补录题面、展示 OCR/解析/队列流水线 |
| 教学能力 | 模型围绕学生给出的当前题讲解并按需返回白名单 GUI spec；本地按精确题目修订、真实学习事实和动作白名单约束 | 未经学生明确请求生成新题/同类题/变式题/校准题；用额外问题独立摸底；本地模板冒充智能讲题 |
| 能力记忆 | 能力只从学生自己给出的题与可信行为中被动形成。记忆跨会话、绑定精确 `problemId + problemRevisionId`，保留尝试、提示、答案暴露、卡住步骤和学习变化；每次只发送当前题真正相关的最小摘要 | 模型直接改 Mastery；把模型省略的事实/归类/关系解释为删除；发送完整数据库或无关历史 |
| 答案暴露 | 每个答案表面绑定精确 `modelTaskRequestId + surfaceKind + responseOrdinal`；只有该表面底部完整进入真实视口才幂等入账 | 以任务成功、内容生成、开始渲染、卡片顶部露出、同回合另一回复可见或重建后重复显示推定暴露 |
| 错题目录 | 可见层级只有 `科目 → 板块 → 知识点`；掌握程度是独立投影视图。总目录为空显示“还没有错题”，筛选为空使用筛选空状态 | 把错因、来源、题型做成分类、筛选、分组、卡片标签或必填字段；在错题本空库显示“暂无学习记录” |
| 真实复习 | 生产初始化为空，不自动写入任何 fixture/seed。所有真实保存且仍为 `ACTIVE` 的错题按规则进入计划；复习展示已保存的精确原始转写/题面修订 | 用演示题填充生产库；由模型生成替代题面、复习题或假答案 |
| 本地/模型 | 当前不部署自建云端；Android 端以 BYOK 直连所选 Provider。模型负责语义理解、归类和讲题；本地负责持久事实、规则、Schema/allowlist、确定性渲染、搜索、排程、导出、授权与密钥 | 把离线智能当主线、建设第二套端侧大模型教学系统、让模型直接写数据库或执行任意动作 |
| 外部执行 | 以不可变类型输入形成逻辑操作指纹；同一逻辑外部操作跨请求最多三次真实 Provider dispatch，本地执行不消耗预算 | 只按 requestId 分别计数、无限重试、把本地失败计入外部预算 |
| 中断恢复 | 应用/进程重建后，持久化 EgressManifest 仅是证据而非发送授权；只有当前进程内有效租约或学生明确点一次“继续”才恢复精确未完成动作。首次拍题的自动启动只能发生一次 | 重建后自动重发、把“继续回复”偷换为“重新规划”、重复首次启动、用假转圈掩盖已暂停 |
| 前端负担 | 每屏一个主任务，只显示继续所需的最小提示；不做手写演算板 | 多个同权主按钮、技术状态刷屏、强制画布演算 |

本文在两份原始 DOCX 与现有产品信息架构的基础上继续外推。原文档是重要证据，不是功能边界。这里用真实场景、学习科学、Android 平台能力、隐私安全和工程成本来判断：前端需要什么、端内后端需要什么、哪些能力必须做成独立大系统、哪些能力明确不做。

## 0. 当前工程落点（2026-07-22，不等于里程碑通过）

M2 已落地一个可运行的“单图 + 持久模型任务 + 结构化候选 + 原图证据”纵切面：系统相机/Photo Picker 的私有 URI 经有界解码、方向校正、EXIF 剥离和 SHA-256 内容寻址形成 `CanonicalSourceAsset`；`CAPTURE_ASSESS` 决定是否可继续，`CAPTURE_PARSE` 返回带块级来源区域、书写层、生产者版本、置信度和 provenance 的 `CapturedQuestionDocument`。模型候选经过本地契约验证后可直接继续，原图和随时修订入口保留；只有缺失导致任务无法继续时才询问最小信息。Markdown/Compose 只是确定性投影，不是权威记录。当前 Fake Gateway 只用于状态与 GUI 演示，不能冒充读取了真实题图；随 APK 打包的印刷文字 OCR 只保留为测试兜底，不再扩成端侧文档理解系统。

当前 Room schema 为 v17。它在 canonical asset、草稿、append-only 草稿修订、正式修订来源关联和提交回执之上，保存 `model_task` 当前事实、事件审计与跨 request 的逻辑外部操作预算，并把讲题答案曝光精确绑定到任务请求、表面和响应序号。用户修订用 basis revision CAS，迟到模型结果不能覆盖人工内容；模型任务用不可变输入指纹、状态/版本 CAS 和并发赢家跟随保证幂等。统一 `CommitProblemDraft` 在一个事务里创建 `Problem`、不可变 `ProblemRevision`、`PracticeUnit`、`ErrorBookEntry` 和来源关联，未知结果只能精确重放原命令。结构化模型文档不会被压成一段文字再保存。

当前 UI 先让用户看到题图已安全收到，正常候选直接进入讲题或错题保存；原图对照与题面修订始终可按需打开，但模型任务状态、内部校验和人工校对字段不作为学生步骤。只有模型判断缺图且任务无法继续时才请求最小补拍；模型不可用时保留草稿并允许稍后重试或更换 Provider，不把学生变成 OCR 录入员。生产代码现已接入受出站授权约束的 OpenAI-compatible 多模态 Provider，并覆盖 `CAPTURE_ASSESS`、`CAPTURE_PARSE`、`PROBLEM_CLASSIFY`、`TUTOR_PLAN` 与 `TUTOR_RESPOND`；测试 Fake 只用于确定性断言，不能冒充真实题图调用。`CAPTURE_PARSE` 已能输出经本地白名单校验的 Cartesian/SymbolTable 图形块，同一结构化图形会由 Compose 与 PDF 分别确定性绘制；无法可靠重建的复杂几何、物理装置或化学图保留原图证据并安全降级，不允许模型猜图。单页 2–12 道独立题已经由模型给出边界、本地裁切并原子生成独立草稿；整卷导入可在一次明确授权后逐对比较相邻页面，只有明确属于同一道题时才由本地事务合并来源束，歧义或失败保留原草稿且不要求学生手工合并、排序。用户真实 Provider 的线上兼容性/质量验收、细粒度手写/印刷/教师批注分层、复杂学科图形、跨页判断质量、语义去重裁决、FTS 分面和 M2 用户研究仍未完成；因此这里记录的是 `IMPLEMENTED-SLICE`，不是 M2 退出结论。端侧不新增模糊度、曝光度等启发式产品门禁；本地只保留安全解码、策略验证、事务、记忆与可恢复任务。

## 1. 最终产品判断

这不是“拍照搜答案 + 聊天框”，也不是把纸质错题本照搬到手机。产品的核心是：

> 把一次错误变成模型可理解、用户可确认、长期可记忆、可讲解、可重做、可调度的学习资产。

首版不需要先建设传统账号和多设备同步服务器，但需要一条受控的任务型 Model Gateway。Android 端不是第二套“本地智能”，而是可信执行与记忆层：

- Room/SQLite 保存权威结构化数据；
- App 私有文件仓库保存原图、派生图和导出物；
- WorkManager 与持久任务表负责可恢复的异步作业；
- 领域服务负责题目真值、作答事实、掌握投影、教学状态机和复习约束；
- `capture.assess`、`capture.parse`、`problem.classify`、`tutor.*`、`review.explain` 等语义任务通过强类型 Model Gateway 接入；其中 `review.explain` 只能解释本地已确定的计划，不能改变候选、顺序或日期；
- Schema、动作白名单、网络策略、用户确认和数据库事务在任何模型结果生效前统一校验和审计。

模型能力是新题图片理解、自动分类、因材施教讲题、关系发现和自然语言总结的前置条件。没有网络、额度或有效 Provider 时，已有题库、历史、搜索、导出、已缓存内容和手工整理仍可用，新任务保存为 `WAITING_FOR_MODEL` 并在恢复后原位续跑；界面不得伪装成还能完成新的智能判断。跨设备同步仍不是首版前置条件。

## 2. “穷举真实情况”的方法

真实情况的笛卡尔积几乎无限，不能靠为每个组合画一张页面来覆盖。系统使用正交维度和稳定状态机，把组合压缩为可测试的等价类。

| 维度 | 需要覆盖的取值 |
|---|---|
| 使用意图 | 求讲解、保存错题、复习、管理、导出、配置、恢复 |
| 时间环境 | 课后、放学后、晚自习、通勤、周末、月考后、考前冲刺 |
| 输入来源 | 相机、相册、系统分享、截图、PDF、已有题；自由文本只用于补充卡点/步骤，不作为 OCR 失败后的题面录入流程 |
| 页面结构 | 单题、多题页、跨页、多小问、双栏、题图、表格、公式 |
| 作答痕迹 | 无作答、学生手写、教师批注、红叉、参考答案覆盖、混合 |
| 学习证据 | 无历史、证据稳定、证据过期、证据冲突、提示依赖、猜测 |
| 设备环境 | GMS/非 GMS、低内存、低存储、低电、发热、横屏、大字号 |
| 连接状态 | 完全离线、弱网、计费网络、API 未配、鉴权失败、限流 |
| 生命周期 | 首次使用、空库、百题、千题、跨版本迁移、恢复备份 |
| 中断与故障 | 权限拒绝、来电、锁屏、进程死亡、重启、部分成功、文件损坏 |
| 人员边界 | 学生本人、家长协助录入、老师只接收导出、家庭共用设备 |
| 隐私状态 | 卷面含姓名/学校、人脸、未满 14 岁、远端处理、数据出境 |

验收时采用三层覆盖：

1. 每个维度的单独状态必须有确定行为；
2. 每对高风险维度必须做组合测试，例如“批量 PDF + 低存储”“讲题 + API 超时”“进程死亡 + 已选择未提交”；
3. 对数据丢失、答案泄露、错误掌握更新和隐私出网做三维以上故障注入。

## 3. 产品不可破坏的 12 条不变量

1. 原始来源资产不可被模型静默覆盖。
2. OCR 与模型输出是建议，不是权威事实。
3. 临时可讲解题目不等于已存入错题本。
4. 只有显式提交的题目才获得稳定 ProblemId。
5. 只有统一 AttemptService 可以追加学习事实。
6. 打开题目、看解析、导入和登录都不算有效作答或打卡。
7. Mastery 只能由 Attempt 投影，模型与 UI 不得直接改写。
8. 讲题中提示后正确与独立正确必须分开记录。
9. 复习调度与考前冲刺是两种队列，不能互相覆盖历史。
10. 所有异步作业都必须可定位、可暂停、可恢复、可部分成功；真实 Provider 重试只能在同一逻辑操作三次 dispatch 总预算内进行。
11. 未经明确策略允许，任何题图、原文、学习档案和密钥不得离端。
12. 合并、修订、软删除、恢复和模型重跑在正常生命周期内必须可追溯；用户发起的永久隐私删除是受控例外，相关事实、审计与可关联标识必须一并清除，不能为了“可追溯”留下影子数据。

## 4. 用户与角色边界

### 4.1 主用户

主用户始终是单一高中生。学习画像、作答历史和复习计划只服务这一位学习者。

### 4.2 家长

首版只支持两类低权限协助：

- 帮学生连续拍摄或导入试卷，生成待整理草稿；
- 接收学生主动导出的本地阶段报告。

家长的拍摄和整理不能形成学生 Attempt，也不能改变 Mastery。

### 4.3 老师

首版没有老师账号、班级后台或实时监控。老师只可能接收学生主动分享的 PDF/Markdown/打印材料。若以后建设老师端，应视为独立产品，不把多角色权限偷偷塞进学生端。

### 4.4 家庭共用设备

MVP 明确提示“本设备按一位学习者建模”。不同学生不能混用同一 Mastery。多档案、账号切换、监护关系和同步冲突是一套极重系统，后置到明确验证需求之后。

## 5. 信息架构裁决

底部导航固定为：

| 栏目 | 稳定用户意图 | 拥有的能力 |
|---|---|---|
| 复习 | 今天该练什么、为什么练、练完如何改期 | 今日计划、复习会话、薄弱点、冲刺、历史、打卡 |
| 讲题 | 这道题卡在哪里、下一步怎么想 | 拍题讲解、已有题讲解、动态互动问题、方法切换、会话历史 |
| 错题本 | 把题保存好、整理好、找得到、能打印 | 录入、按需修订、科目→板块→知识点、掌握程度独立视图、搜索、详情、批量、导出 |
| 我的 | 我学得怎样、智能能力如何配置、数据如何控制 | 学习档案、Provider/API、学习记忆、隐私、提醒、存储、备份与导出 |

以下不是底栏：

- 拍照：共享输入能力，必须先带 teach 或 save 意图；
- 打卡：完成有效复习后的派生结果；
- 搜索：错题本内工具；
- 待处理：错题本中仅在确有未完成任务时出现的恢复队列；不是强制校对入口；
- 统计：我的上半部分；
- 打印/导出：错题本批量动作；
- 聊天历史：讲题二级页；
- 日历：复习二级视图。

### 5.1 共享全屏流程

| 路由 | 作用 | 是否显示底栏 |
|---|---|---|
| capture/{draftId}?intent=save\|teach | 相机、相册、裁切与质检 | 否 |
| import/{jobId} | PDF/多页导入进度 | 否 |
| correction/{draftId} | 仅当学生主动指出题面有误时按需修订；不是正常路径必经页 | 否 |
| problem/{problemId} | 权威题目详情 | 视来源 |
| review-session/{planId} | 沉浸式复习 | 否 |
| tutor/session/{sessionId} | 讲题根栈内的会话与历史恢复 | 是，始终属于“讲题”底栏 |
| tutor/session/{sessionId}/focus | 长推导、题图/图形查看、答案核对等沉浸子流程 | 否 |
| duplicate-resolution/{candidateId} | 重复/修订/变式处理 | 否 |
| export/{selectionId} | 排版、答案策略和生成任务 | 否 |
| backup-restore/{jobId} | 校验、预览、迁移和恢复 | 否 |
| settings/intelligence | Provider、API、模型能力测试、费用与发送范围 | 否 |
| settings/data-privacy | 网络、保留、备份与删除策略 | 否 |

### 5.2 Android 外部入口

- 系统分享图片或 PDF：先选择“讲解”或“保存”，只选择一次；
- 通知：深链到复习根页或已生成的复习会话；
- 系统照片选择器：相机拒权后的首选替代路径；
- Storage Access Framework：导入 PDF、导出备份和生成文档；
- 可后置验证的桌面快捷方式：拍题讲解、录入错题、开始复习。

不设置含义模糊的全局相机 FAB。

## 6. 真实场景目录

下面列出必须在设计、实现和测试中可追溯的场景族。页面只负责呈现状态，不能让每个场景复制一套业务逻辑。

### 6.1 首次使用与采集

| ID | 真实目标 | 关键系统行为 |
|---|---|---|
| CAP-01 | 首次打开马上试用 | 不登录、不先问权限；空态一击进入录入或讲题 |
| CAP-02 | 课后收录一题 | 拍后先落盘；可用候选直接保存，只有学生主动发现题面有误时才修订，不做 OCR 确认关卡 |
| CAP-03 | 晚自习马上求讲解 | 形成临时快照和会话，默认不写 Problem |
| CAP-04 | 学校禁手机，放学后补录 | 支持延迟录入和来源日期，不用断签惩罚 |
| CAP-05 | 试卷马上要交，连续拍几页 | 每页独立保存；可先保存后处理 |
| CAP-06 | 月考后整理整张试卷 | 多页任务、逐页状态、错题候选、例外确认 |
| CAP-07 | 一张图有多道题 | 自动生成独立待处理草稿并保留原图；失败只需重新整理，不要求学生框选 |
| CAP-08 | 一题跨页或有多个小问 | 模型给出同题跨页候选；能继续则直接处理，无法继续时只请求最小补拍，最终只生成一个 Problem |
| CAP-09 | 模糊、反光、歪斜、遮挡或截断 | 模型判断是否影响当前任务；能继续则不打断，不能继续时只给重拍/补拍/换图/稍后继续并保留草稿 |
| CAP-10 | 学生答案和教师批注覆盖题面 | 原图、干净题面、作答层和批注层分离 |
| CAP-11 | 从截图、相册、分享或 PDF 导入 | 统一 Draft/Job 管线，按意图分流 |
| CAP-12 | 题目重复或只改了条件 | 提供新一次错误、附加来源、新修订、建立变式或独立保存 |
| CAP-13 | 黑板、投影、屏摄低清图片 | 模型评估内容完整性；失败保留草稿并引导换图或重拍，不能要求学生替系统补录题面 |
| CAP-14 | 相机权限拒绝 | 立即给照片选择器和设置入口，不循环弹窗，也不把手工录题作为权限失败退路 |
| CAP-15 | 拍摄时低电、发热或低内存 | 首要保证原图落盘，重处理排队 |
| CAP-16 | 空间不足 | 拍前预警，先清缓存/旧导出，不自动删原图 |

### 6.2 自适应讲题

| ID | 真实目标 | 关键系统行为 |
|---|---|---|
| TUT-01 | 从已有错题求助 | 直接复用 Problem，不重复 OCR |
| TUT-02 | 获得符合水平的第一步讲解 | 只读取与当前题直接相关的 Mastery、近期独立作答、提示依赖、答案暴露和卡住步骤；缺少关联时发空的跨题摘要 |
| TUT-03 | 已掌握基础，不想被问弱智题 | 在学生当前题内跳过稳定掌握项，直接处理真实卡点、边界或方法比较；不另造变式 |
| TUT-04 | 数据不足 | 不贴能力标签、不追加诊断题；依据当前题给最小有用提示或方法路线，让后续真实行为自然形成证据 |
| TUT-05 | 点击单选/多选/排序 | 记录选择、用时、信心、提示层级和选择时掌握快照 |
| TUT-06 | 答对但可能靠猜 | 允许“我确定/有点猜”，降低证据强度 |
| TUT-07 | 连续答错 | 动态切换符号表、图像、类比、拆步或换方法 |
| TUT-08 | 想换一种方法 | 动作由题型与当前失败路径动态生成，不写死 |
| TUT-09 | 时间紧，明确索要答案 | 直接给可信完整解法；请求动作本身不算答案曝光，仍须等精确答案表面底部完整进入真实视口才记录 |
| TUT-10 | OCR 错、条件缺失或参考答案冲突 | 进入澄清/修订提案，不继续基于错误题面推理 |
| TUT-11 | Provider 未配、超时、限额或缺少所需能力 | 保存请求并回到配置，能力恢复后从原教学状态继续 |
| TUT-12 | 来电、锁屏、进程死亡 | 恢复输入、图片、已选项、提示层级和已确认 block |
| TUT-13 | 讲完决定入库 | 显式存入错题本，复用按需修订和提交路径，幂等；正常候选不经过 OCR 确认页 |
| TUT-14 | 题目不适合选择题 | 使用简短输入、步骤排序、图上点选或让用户在纸上完成 |
| TUT-15 | 多个选项都可能成立 | 阻止提交为客观单选，转为澄清或多选 |
| TUT-16 | 模型产生不可信解释 | 结构校验、数学/单位校验、来源标记和安全回退 |
| TUT-17 | 直接查看完整答案后离开，未提交任何选项 | 只有绑定精确模型任务/表面/响应序号的答案表面底部完整进入真实视口才写入终态暴露结果；只更新题目记忆和短期复习，不更新知识掌握 |

### 6.3 复习、推送与打卡

| ID | 真实目标 | 关键系统行为 |
|---|---|---|
| REV-01 | 完成今日到期题 | 冷启动一击开始，默认按约 10 分钟组织 |
| REV-02 | 只有 2–5 分钟且单手 | 时间预算、下半屏主控、随时结束 |
| REV-03 | 多日未复习、积压很多 | 先给可完成的小队列，不羞辱、不无限红点 |
| REV-04 | 今天没有到期题 | 显示已完成/暂无到期；若学生主动继续，只能从真实 `ACTIVE` 错题选择，不生成新题 |
| REV-05 | 再次做错 | 记录 Attempt，可进讲题，返回保持复习状态 |
| REV-06 | 提示后答对、看答案或猜中 | 证据类型分开，不直接标记已掌握 |
| REV-07 | 长计算题要用纸 | 支持纸上作答、自评和可选拍结果 |
| REV-08 | 考前冲刺 | 临时 SprintPlan，不破坏长期排程 |
| REV-09 | 每日打卡 | 只有至少一个有效 Attempt 才成立 |
| REV-10 | 通知拒绝/勿扰/作息改变 | 应用内计划不受影响；提醒只是一种入口 |
| REV-11 | 时区或系统时间改变 | UTC 事实 + 本地学习日，避免重复计划/打卡 |
| REV-12 | 调度算法升级 | 版本化投影，预览负担变化，保留旧 Attempt |
| REV-13 | 多道同源/同知识点题挤在一起 | 同源和 sibling 去重/分散，避免提示效应 |
| REV-14 | 中途切计算器或锁屏 | 暂停有效用时，恢复同一题 |

### 6.4 错题本管理

| ID | 真实目标 | 关键系统行为 |
|---|---|---|
| LIB-01 | 空库或有未整理草稿 | 分清“还没有题”和“有 N 道待整理” |
| LIB-02 | 在千题中找目标 | FTS、科目→板块→知识点层级、独立掌握程度与最近筛选；不提供错因/来源/题型分面 |
| LIB-03 | 查看并修正模型归类 | 只修正科目、板块、知识点；模型建议与用户覆盖并存，省略不删除既有绑定 |
| LIB-04 | 修改题干或题图 | 建立 ProblemRevision，不覆盖历史；原图仍是证据但不成为分类 |
| LIB-05 | 合并重复题或建立变式关系 | 保留来源、Attempt 和可撤销映射 |
| LIB-06 | 误删或批量清理 | 软删除、回收站、批量数量确认 |
| LIB-07 | 打印纸质复练 | 选择题、答案策略、排版预览、失败项报告 |
| LIB-08 | 空间不足或文件损坏 | 分开原图、缓存、模型、导出物；给出影响范围 |
| LIB-09 | 周末处理未完成草稿 | 批量恢复任务结果；可用候选直接继续，只有无法继续的条目才逐题重拍/补拍/换图，不做批量 OCR 确认 |
| LIB-10 | 分类体系升级 | 保留旧标签和映射版本，不静默重写用户修正 |
| LIB-11 | 一题多知识点/跨学科 | 多绑定与主次权重，不强塞单标签 |
| LIB-12 | 搜索无结果 | 保留当前条件并提供清除筛选、返回全部错题或拍照上传；不暴露 OCR/来源分类 |

### 6.5 我的、配置和数据生命周期

| ID | 真实目标 | 关键系统行为 |
|---|---|---|
| ME-01 | 看自己是否进步 | 展示掌握、薄弱点和变化，并能回到相关题/行动 |
| ME-02 | 数据不足 | 显示“暂无学习记录”或隐藏画像，不展示虚假趋势，也不提示“还差多少题”制造摸底压力 |
| ME-03 | 升年级、换教材或新增学科 | 保留历史，映射新知识体系，不改旧事实 |
| ME-04 | 配置自带模型 API | 服务商、地址、模型、密钥、能力测试、任务级授权 |
| ME-05 | 切换 Provider 或模型 | 图片/结构化输出能力、费用、数据政策、合成测试、失败回滚 |
| ME-06 | 家长协助拍试卷 | 只产生 Asset/Draft，不产生学生 Attempt |
| ME-07 | 导出阶段报告 | 本地生成、分享前预览隐私字段 |
| ME-08 | 换机、重装或恢复备份 | 校验、预览、迁移、临时库恢复、原子切换 |
| ME-09 | 裁掉姓名、学校、班级 | 本地脱敏派生副本，远端处理前再次确认 |
| ME-10 | 导出全部数据或彻底删除 | 范围可核对、可重试、结果可验证 |
| ME-11 | 左手、大字体、读屏和色觉差异 | 200% 字体与状态非颜色依赖 |
| ME-12 | 多人共用一台设备 | MVP 阻止画像混用；完整多档案系统后置 |
| ME-13 | API Key 失效或服务商换模型 | 不丢草稿；重新测试并返回原任务 |
| ME-14 | 内部验证网络硬边界 | `strictOffline` 仅作为无 INTERNET 权限的边界测试变体，不建设面向学生的第二套离线智能产品 |

## 7. 系统依赖总图

~~~mermaid
flowchart TB
    UI["Compose UI：四栏与共享全屏流程"]
    APP["应用用例：命令、查询、状态机、幂等"]
    DOMAIN["核心领域：Problem / Attempt / Mastery / Review / Tutor"]

    DB["Room / SQLite / FTS"]
    FILES["私有文件与内容寻址资产仓库"]
    JOBS["持久 Job 表 + WorkManager"]
    CAPTURE["CameraX / Photo Picker / SAF"]
    DOC_MODEL["模型图片理解适配器"]
    MODEL["任务型 Model Gateway / 用户模型 Provider"]
    RENDER["Markdown / LaTeX / 交互块 / PDF"]
    SECURITY["NetworkPolicy / Keystore / 数据脱敏"]

    UI --> APP
    APP --> DOMAIN
    APP --> DB
    APP --> FILES
    APP --> JOBS
    CAPTURE --> APP
    DOC_MODEL --> APP
    MODEL --> APP
    APP --> RENDER
    DOC_MODEL --> SECURITY
    MODEL --> SECURITY

    DB -.实现端口.-> DOMAIN
    FILES -.实现端口.-> DOMAIN
~~~

依赖必须朝向稳定领域。Compose、Room、CameraX、WorkManager、具体模型 SDK 和 API 客户端都是可替换插件，不能成为 Problem、Attempt、Mastery 等核心类型的依赖。

### 7.1 前端功能到端内后端的依赖矩阵

| 前端任务 | 必须读取 | 唯一写入命令 | 异步/大型依赖 | 安全降级 |
|---|---|---|---|---|
| 复习首页 | ReviewPlan、MasteryProjection、未完成 ReviewSession、Problem 摘要 | `StartOrResumeReviewSession`、调整时间预算 | Review Planner；通知只是入口 | 调度重算失败时读取最近成功计划并标明时间 |
| 提交复习答案 | PracticeUnit、当前题修订、答案规范、提示状态、当前 ReviewSession 版本 | `RecordReviewAttemptAndAdvance` 在一个事务内写 Attempt/outbox、推进精确队列项并落 receipt；随后由投影器更新画像和排程 | Mastery Projector、Review Planner | 普通 Attempt 不可复用来推进；learner 多活动会话或题目错配时 fail-closed；投影延迟不回滚已提交事务 |
| 复习中求讲解 | 当前 ReviewSession、精确 ProblemRevision、当前 Attempt 草稿、相关最小记忆 | `OpenTutorFromReview`；讲题返回后仍由 `RecordAttempt` 收口 | Tutor Policy、Model Gateway、可选可信答案证据 | 无模型时保留已保存原题、复习位置与缓存讲解，暂停新智能讲解；不得用本地模板或生成替代题冒充 |
| 讲题拍照 | 临时 CaptureSession、与当前题相关的最小 LearnerSnapshot、可用能力 | `CreateEphemeralTutorProblem`；只有显式保存才 `CommitProblemDraft` | 采集、模型图片理解、Tutor Policy、Model Gateway | 识别失败时保留草稿，只给重试、重拍/补拍、换图或稍后继续；不要求框选/手工补录题面 |
| 讲题互动选择 | TutorSession、AdaptiveDecision、TutorAssessmentItem、答案权限 | `RecordTutorInteraction`；只有 `assessmentEligibility=ATTEMPT_ELIGIBLE` 才派生 `RecordAttempt` | Adaptive Selector、内容验证器 | 选项不唯一/不可信时转成澄清、短答或已验证讲解 |
| 换种方法/要提示/完整讲解 | 当前教学目标、已用方法、提示层级、用户请求 | `AdvanceTutorStrategy`；答案暴露由精确表面可见性事实单独写入 | 本地策略约束 + 模型语义讲解 | 没有可信替代法时明确说明，不生成伪方法；任务成功或开始渲染不提前记答案暴露 |
| 讲题存入错题本 | 临时来源、题面修订、会话关系、重复候选 | `CommitProblemDraft`，使用幂等键并关联 TutorSession | 去重、分类、索引可后台运行 | 智能整理失败不影响正式题目可见 |
| 错题本直接拍照 | CaptureSession、默认学科上下文 | `SaveCaptureDraft` → 可用候选直接 `CommitProblemDraft`；用户主动指出题面有误时才修订 | 模型图片理解、科目/板块/知识点归类、去重 | 先落 CanonicalSourceAsset/草稿；无法继续时重试、重拍/补拍、换图或稍后继续；不手录、不启动讲解 |
| 搜索与筛选 | FTS、科目/板块/知识点绑定、Mastery/Review 投影 | 只写最近筛选或用户归类覆盖 | FTS 重建、归类作业 | 索引异常时回退基础列表与科目/板块/知识点目录；不新增来源/错因/题型分面 |
| 编辑/合并/拆分/删除 | ProblemRevision、关系、引用、Attempt 历史、Asset 引用计数 | `ReviseProblem`、`MergeProblem`、`SplitProblem`、`MoveProblemToTrash` | 重建索引和投影 | 先提交可撤销领域事务，再后台刷新派生数据 |
| 批量导入 | SourceBundle、页面状态、容量预检、Draft 列表 | `CreateImportBatch`、逐页 `SaveCaptureDraft` | PDF 渲染、模型图片理解/转写/归类、去重 | 单页失败不回滚成功页；可暂停、恢复和跳过 |
| 导出/打印 | 固定 selectionSnapshot、题目修订、答案策略、模板 | `CreateExportJob` | Renderer、分页、系统打印 | 单题失败列入报告，其余继续；不静默换修订 |
| 我的学习数据 | Attempt Ledger、Mastery/Review/Analytics 投影 | 学习目标、范围和画像重置使用各自领域命令 | Analytics Projector | 数据不足显示“暂无学习记录”或隐藏模块，不出额外题补证据、不伪造趋势 |
| 智能能力/模型设置 | AppCapabilitySnapshot、ProviderPolicy、网络活动摘要、Provider 能力 | `StoreCredential`、`UpdateProviderPolicy`、`TestProviderCapability` | Keystore、Model Gateway、合成能力测试 | 失败不影响已有题库；未完成的新智能任务保持待续跑 |
| 备份/恢复/删除全部 | 数据清单、AssetManifest、schemaVersion、空间预检 | `CreateBackup`、`RestoreIntoStaging`、`EraseAllUserData` | 快照、加密、迁移、完整性校验 | 恢复只在临时库验证后原子切换；删除失败可核对并重试 |

这张表同时约束 UI 文案：界面只能声称唯一写入命令已经完成的事实。例如题目已提交但分类作业仍在跑时，显示“已保存，智能整理中”，不能一直锁住页面，也不能提前显示“分类完成”。

## 8. 逻辑模块与投资矩阵

复杂度定义：

- S：页面或薄适配器；
- M：明确状态机、事务或后台任务；
- L：跨多个流程、需要迁移和系统测试；
- XL：独立研究/平台型系统，需要数据集、评测、模型或服务基础设施。

| 逻辑模块 | 必要性 | 复杂度 | 唯一职责 | 决策 |
|---|---|---:|---|---|
| App Shell 与导航 | MUST | M | 四个根栈、深链、恢复入口 | 首批 |
| UI 设计系统 | MUST | M | 暖纸墨色 token、组件、无障碍 | 首批 |
| Problem/Revision 核心模型 | MUST | L | 权威题目与修订 | 首批 |
| Asset 文件仓库 | MUST | L | 原图、派生图、引用计数、完整性 | 首批 |
| Capture Session | MUST | L | 相机/相册/分享输入、安全落盘与意图恢复；内容可用性由模型判断 | 首批 |
| Intake/On-demand Revision | MUST | L | Draft 可用性、用户主动修订和原子提交；正常路径不设 OCR 校对门 | 首批 |
| Problem Library | MUST | L | 列表、详情、修订、删除、关系 | 首批 |
| FTS 与分面检索 | MUST | M/L | 千题本地搜索与筛选 | 首批 |
| Attempt Ledger | MUST | L | 追加式作答事实与撤销事件 | 首批 |
| Mastery Projector | MUST | L | 题目/知识点/学科画像 | 首批规则版 |
| Review Planner/Session | MUST | L | 日常排程、冲刺、恢复、改期解释 | 首批 |
| Structured Renderer | MUST | L | Markdown、LaTeX、互动 block、题图 | 首批 |
| Tutor Session | MUST | L | 会话、提示层级、动态动作、可选保存 | 首批 |
| Verified Teaching Artifact | MUST for tutor | L | 可信答案、解法、误区和评测材料的来源/版本/验证 | 先做小范围可信垂直切片 |
| Tutor Capability Gate | MUST | M | 决定当前题能否澄清、讲解、评测或更新画像 | 首批，不允许 UI 越权承诺 |
| Current-problem Tutor Policy | MUST | L | 跳过已掌握内容，在用户当前题内选择有区分度的讲法/交互，不生成独立测验题 | 本地约束 + 模型语义能力 |
| Mistake Taxonomy Organizer | SHOULD | L | 只给出科目→板块→知识点建议并保留用户修正；掌握程度独立 | 模型输出 + 本地合并契约 |
| Persistent Job Engine | MUST | L | 模型任务、批量、导出、备份的可恢复执行 | 首批 |
| Network/Model Gateway | MUST | L | 能力路由、结构校验、最小数据出网 | 首批接口，适配器渐进 |
| Local Model Manager | LATER/NOT FOCUS | XL | 仅在未来独立研究中评估端侧模型包 | 不进入当前产品主线 |
| Document Understanding | SHOULD | XL | 切题、跨页、手写/批注、公式/图形 | 分阶段研究系统 |
| Export/Print | SHOULD | L | PDF/Markdown/系统打印 | 核心闭环稳定后 |
| Backup/Restore | MUST before beta | L | 加密包、校验、迁移、原子恢复 | Beta 门槛 |
| Notification | SHOULD | M | 本地提醒和深链 | 首次完成复习后启用 |
| Analytics Projection | MUST | M | 我的学习数据，不复制事实 | 首批 |
| Diagnostics/Audit | MUST | M | 本地任务与模型运行可解释日志 | 首批轻量版 |
| Cloud Sync/Account | LATER | XL | 多端、一致性、冲突、身份 | 不进入 MVP |
| Teacher/Parent Portal | LATER | XL | 多角色、班级、权限、监护 | 独立产品验证 |
| Social/Ranking/Marketplace | DO NOT BUILD | XL | 与核心学习闭环无关 | 排除 |
| Self-hosted LLM Platform | DO NOT BUILD | XL | 训练、推理集群、计费与运维 | 使用可替换外部适配器 |

### 8.1 哪些能力本质上是“大系统”

以下能力不能被当作几个页面或一次模型调用：

1. 文档理解：需要采集质检、切题、跨页、手写/印刷/批注分层、公式、图形、置信度校准和人工回流。
2. 当前题自适应教学：需要相关学习证据、卡住步骤、提示策略、当前题内有区分度的交互、结构化输出和长期评测；不建设独立出题/摸底系统。
3. 任务型 Model Gateway 与评测：需要 Provider 能力探测、强类型 Schema、流式提交、重试/取消、费用提示、数据范围和金标回归。
4. 跨设备同步：需要身份、端到端加密、题目修订冲突、追加事件合并、资产传输和删除传播。
5. 备份恢复：需要一致性快照、清单校验、格式迁移、临时恢复库、原子切换和失败回滚。
6. 通用理科图形理解：几何、电路、力学、化学结构和实验装置必须是不同解析器，不能用一个 VLM 口头描述代替。

### 8.2 明确不建设（DO-NOT-BUILD）

- 通用首页或第五个底栏；
- 答案优先的聊天机器人；
- 写死的讲题快捷动作；
- 不读数据库、所有人同一套基础问法；
- 模型直接修改 Mastery、ReviewPlan 或题库主记录；
- 强制完整录题表单；
- 课程商城、直播课、真人问答和社区；
- 排行榜、公开成绩、断签羞辱和无限红点；
- 默认账号、默认同步、默认上传题图；
- 家长实时监控和老师班级后台；
- 语音虚拟人、宠物养成和学习币；
- 自研支付、订阅和广告系统；
- 通用爬题库或版权不明的公共题库；
- 为摸索能力而生成诊断题、校准题、同类题或变式题；
- 生产初始化 seed/fixture，或用生成题替代真实 `ACTIVE` 错题复习；
- 识别失败时把学生引向手工补录题面；
- 把错因、来源、题型作为错题分类或检索分面；
- 应用重建后仅凭持久 EgressManifest 自动重发外部请求；
- 首版承诺所有手写和理科图形完美重建；
- 把算法名和模型名直接暴露给学生。

## 9. 核心领域与数据模型

### 9.1 本地学习者与目标

首版产品只有一个活动学习者，但所有学习数据仍携带 profileId，避免未来迁移时把“单用户”写死进表结构。

| 对象 | 主要字段 | 说明 |
|---|---|---|
| LocalStudentProfile | profileId、年龄段、当前学段 | 不保存真实姓名、学校或学籍 |
| StudyGoal | 考试日期、科目、每日时间预算 | 冲刺计划的输入，不是打卡压力工具 |
| LearningPreference | 提示偏好、答案揭示、左右手、大字号 | UI 和教学策略只读 |
| NetworkPolicy | 能力、目的地、数据类别、同意版本 | 所有出网适配器的统一门禁 |
| RetentionPolicy | 临时图、会话、日志、回收站期限 | 由删除协调器执行 |

### 9.2 来源材料与批量试卷

~~~text
SourceBundle
├─ SourceDocument
│  ├─ SourcePage
│  │  ├─ RawCaptureAsset        临时、短期
│  │  ├─ CanonicalSourceAsset  已裁剪脱敏、不可变
│  │  └─ DerivedAsset          矫正、增强、缩略图、掩码
│  └─ 页面顺序与坐标变换
├─ QuestionCandidate
├─ QuestionGroupCandidate
└─ ImportBatchItem
~~~

SourceBundle 可以表示单张拍题、相册多选、整套 PDF、题目页与答案页、跨页题和系统分享的一组文件。

当前生产切片已实现“单道题跨页”的有序 SourceBundle：用户可在原草稿内继续拍照或从相册补页，Room v9 原子追加并恢复每页来源；每页分别通过模型质量检查，较早页面的 `NEED_MORE_IMAGE` 可由后一页满足，最后一页仍不完整时继续拦截，全部满足后才联合转写。PDF/整卷导入仍保持独立入口；页面全部安全落盘后，学生一次确认整组题图范围，模型只比较相邻两页并返回同题、下一题或不确定，本地以稳定顺序处理。明确同题时在一个 Room 写事务中追加来源页、废弃被合并草稿并重映射批次页；不确定、模型失败、配置变化或事务前状态变化均保留原页。

重要裁决：

- 相机完整画面先进入带 expiresAt 的 CaptureRecoveryBuffer，最长 24 小时；
- 用户确认的裁切/脱敏题目区域成为不可变 CanonicalSourceAsset；
- 没有持久 CanonicalSourceAsset 就不能创建永久 ProblemDraft；用户点击“先保存这些页”时，必须先确认将当前整页去 EXIF 后作为 CanonicalSourceAsset 保留；
- 默认不长期保存包含姓名、同学人脸和教室背景的完整画面；
- 任何 OCR 坐标都能映射回 CanonicalSourceAsset；
- 单页自动切题直接生成独立待处理草稿；整卷连续页只在模型明确判为同一道题且本地状态仍一致时自动合并。边界不合格、判断不确定或本地写入失败时保留原图和原草稿，不把合并、拆分、旋转、重排设为学生必经步骤。

### 9.3 权威题目与评分单元

~~~text
Problem                        稳定身份
└─ ProblemRevision             不可变修订
   ├─ SharedStimulus           共用材料、文章、图表
   ├─ ProblemPart[]            主问题与小问树
   ├─ ContentBlock[]           文字、公式、选项、图、表
   ├─ AnswerSpec[]             答案与权威来源
   ├─ SolutionSpec[]           解析与权威来源
   └─ SourceReference[]        页、区域、坐标、版本

PracticeUnit
├─ 整题
├─ 某个 ProblemPart
└─ 同一 SharedStimulus 下的一组小问
~~~

新增 PracticeUnit 是必要的：学生可能只错第 2 小问，复习和 Attempt 不能永远按整道 Problem 计分。

AnswerSpec 的来源至少包括：

- USER_CONFIRMED
- TEACHER_KEY
- SOURCE_DOCUMENT
- MODEL_SUGGESTED
- UNKNOWN

只有经过验证的答案可以自动评分。答案未知、主观题或多解题走用户自评、评分量规或人工确认。

`USER_CONFIRMED` 只表示“用户确认当前录入内容”，不自动等于答案正确。只有附带教师/来源文档证据，或通过独立确定性验证后，才能升级为自动评分所需的 VERIFIED；用户自行填写但无法外部核验的答案最多支持 SELF_REPORT，不得作为客观选择题的正确项权威。

### 9.4 题目内容块

ContentBlock 是判别联合，不使用“任意 HTML”：

| 类型 | 结构 |
|---|---|
| Paragraph | Unicode 文本、语言、段落样式 |
| Formula | 原始 LaTeX、规范化 AST、可选 MathML、验证状态 |
| ChoiceGroup | 单选/多选、选项 AST、正确性来源、干扰项误区 |
| Table | 行列、跨度、单元格内容 |
| Figure | 原始裁图、类型、可选 scene graph、验证状态 |
| SharedStimulusRef | 指向共用材料 |
| Blank/Input | 输入类型、约束、单位、评分规则 |

Markdown 和 LaTeX 是这个结构的显示投影，不是数据库唯一真相源。

### 9.4.1 可信答案证据、讲题边界与评测快照

任意题目能够被识别，不等于系统拥有可自动评分的标准答案，更不等于可以据此评价学生。模型仍可围绕用户当前题提供讲解、提示和按需 GUI；可信材料链只控制“能否自动判分、形成 Attempt 或改变掌握投影”，不能把普通模型讲题降级成本地模板：

~~~text
VerifiedTeachingArtifact
- artifactId / artifactRevision
- subjectRef: SavedPracticeUnitRef | TutorContentSnapshotRef
- answerSpecSnapshotId
- solutionStepAst[]
- misconceptionEvidence[]
- assessmentItemIds[]
- coverage: ANSWER_ONLY | EXPLANATION | HINTS | ASSESSMENT
- sourceType
- provenance / rights
- validatorResults[]
- verificationStatus
- createdAt
~~~

允许作为评分/掌握证据的来源按可信度分层：

1. 人工审核的教学材料；随应用打包的材料不得自动写入生产错题库或复习计划；
2. 教师答案、来源文档答案，并由用户确认系统转录无误；
3. 对受支持题型可复算的确定性求解器或规则模板；
4. 模型生成内容只有经独立来源、求解器或人工验证后才能用于自动评分；其未验证讲解仍只是当前会话候选；
5. 只有模型自报置信度的内容永远不能成为 VerifiedTeachingArtifact。

用户无外部证据自行填写的答案保留为 USER_ASSERTED，只能用于讨论或明确标记的自评，不获得 AUTO_VERIFIED 资格。

没有可信材料时，系统仍可让模型讲当前题并明确不确定性，但不得自动判分或把互动写成掌握证据。生产初始化必须保持真实空库；测试 fixture 只能由测试/显式演示环境加载，不能成为真实复习题。识别失败时也不得要求学生手工补录题面或答案来解锁流程。

TutorCapabilityGate 对每次会话持久化：

~~~text
TutorCapabilityDecision
- canClarify
- canExplain
- canOfferHints
- canAssess
- canUpdateMastery
- artifactRevisionIds[]
- learnerSnapshotVersion
- reasonCodes[]
- policyVersion
~~~

UI 的“开始讲解”和“用于学习画像”必须分开：模型可围绕当前题讲解；只有自动评分和学习画像更新受可信证据位控制。可点击交互只是当前题讲解形式，不因聊天框存在就默认成为可评分题。

讲题中的可点击交互使用不可变 TutorAssessmentItem；它只能引用用户当前题的精确修订和本轮教学分支。没有用户明确要求时，不得引用另一道题、题库候选或生成题来探测能力：

~~~text
TutorAssessmentItem
- assessmentItemId / itemRevision
- tutorContentSnapshotId
- optional savedPracticeUnitRef
- teachingArtifactId / answerSpecSnapshotId
- promptAst / optionAst[] / presentedOptionOrder[]
- verificationStatus / safeToAssess
- assessmentEligibility: SESSION_ONLY | ATTEMPT_ELIGIBLE | BLOCKED
- scoringMode: AUTO_VERIFIED | USER_SELF_REPORT | RUBRIC_ASSISTED
- learnerSnapshotVersion / projectionCheckpoint
- hintLevelAtPresentation
- answerSurfaceState / modelTaskRequestId / surfaceKind / responseOrdinal
- createdAt
~~~

TutorAssessmentItem 只冻结“展示时”状态，不能提前永久决定提交资格。用户展示后可能再点提示、查看完整答案或切换会话，因此互动事件必须追加并排序：

~~~text
TutorAssessmentEvent
- eventSequence
- eventType: PRESENTED | HINT_REVEALED | ANSWER_PRESENTATION_REQUESTED |
             ANSWER_SURFACE_EXPOSED | RESPONSE_SUBMITTED | CANCELLED
- hintLevel?
- submittedResponse?
- occurredAt

AssessmentSubmissionContext
- assessmentItemId / itemRevision
- eventsThroughSequence
- firstSubmittedResponse / firstResponseAt
- maxHintLevelBeforeFirstResponse
- answerSurfaceExposedBeforeFirstResponse
- answerSurfaceExposedBeforeSubmit
- contentVerificationAtSubmit
- learningEvidencePolicyAtSubmit
- derivedAssessmentEligibility
- evidenceWeight
~~~

首次“提交”而不是首次点选才形成 firstResponse；选中未提交仍可修改。AttemptService 必须在同一事务里锁定/校验 `eventsThroughSequence`，重新派生 AssessmentSubmissionContext，并把派生结果连同 AttemptEvent 一起提交。展示时的 `assessmentEligibility` 只是上限：任何先于首次提交的 `ANSWER_SURFACE_EXPOSED` 都令正向独立证据权重为 0；仅有 `ANSWER_PRESENTATION_REQUESTED` 不算暴露。提示/暴露与提交并发时以数据库 eventSequence 为唯一顺序，不能相信 UI 传来的布尔值；未验证、SESSION_ONLY 或事件序列冲突时拒绝写 Attempt。

`RevealHint`/`RevealAnswer` 的“请求展示”与“实际暴露”必须分开。TutorSessionService 可以先持久化展示意图和精确答案表面身份，再把内容交给 UI；但这一步、模型任务成功或开始渲染都不能写 `ANSWER_REVEALED`。只有本地布局观测确认绑定 `modelTaskRequestId + surfaceKind + responseOrdinal` 的答案表面底部完整进入真实视口后，才追加 `ANSWER_SURFACE_EXPOSED`。若意图已提交但界面在实际可见前崩溃，恢复后仍是未暴露，不能以“可能看过”代替证据。

`AnswerRevealOutcome` / `TutorAnswerExposureOutcome` 只能由上述精确可见性事实幂等产生，不能与展示意图同事务预写。它只把对应 PracticeUnit 记为 `ANSWER_REVEALED`、安排短期复习并增加揭示计数，绝不更新知识掌握。同回合另一条答案回复可见、卡片顶部露出或重建后重复显示都不能替代精确表面证据。用户之后仍提交答案时，首次真实响应的 ordinal 仍为 1。

未存入错题本的题也可以在用户的长期学习证据策略为 LONG_TERM、内容已验证且保留最小脱敏评测快照时形成 Attempt；选择 SESSION_ONLY、内容未验证、已先揭示答案或没有稳定 AnswerSpec 时，只记录 TutorInteraction。是否存入错题本与是否形成学习证据是两个独立选择，隐私页必须解释并允许用户改为仅本次会话。

### 9.5 分类与关系

错题目录归类分两层：

- ClassificationSuggestion：模型建议，可过期、可拒绝；
- ProblemClassificationBinding：已接受的正式绑定。

每个建议记录 dimension、labelId、displayName、confidence、evidence、taxonomyVersion、modelArtifactId 和 reviewStatus；这些内部字段不直接展示给学生。

正式且学生可见的目录维度只有：

- 科目；
- 科目内板块；
- 板块下一个或多个知识点。

掌握状态和复习到期不是分类标签，它们来自学习投影并作为独立视图/筛选。错因、来源、题型、年级、教材范围、表征形式和认知要求都不得成为错题目录绑定、筛选、分组、卡片标签或必填字段；来源资产只作为题面证据。模型整理与用户保存采用合并语义：模型省略现有绑定不等于删除，只有学生明确纠正才可移除。

关系同样分建议与正式关系，至少支持：

- SAME_KNOWLEDGE
- VARIANT_OF
- PREREQUISITE_OF
- SHARES_STIMULUS
- CONTINUATION_OF
- ANSWER_FOR
- SAME_FIGURE_PATTERN
- POSSIBLE_DUPLICATE
- DERIVED_FROM

每个关系保存 basisRevisionIds。关系整理只能由学生明确发起，只能连接本机已有题目，不能借关系生成新题或摸索能力。题目修订后，受影响的旧建议进入 STALE，不能继续静默生效；模型省略既有关系不表示删除。

### 9.6 学习事实与派生状态

~~~text
AttemptSubmission
    ↓
AssessmentDecision
    ↓
AttemptEvent
    ├─ AttemptCorrection
    ├─ HintEvent
    └─ ConfidenceReport
        ↓
ProblemMemoryState
KnowledgeMasteryState
SubjectAbilityState
        ↓
ReviewPlan / AnalyticsProjection
~~~

规则：

- 原始作答和更正都只追加；
- “撤销刚才结果”生成 AttemptCorrection，不删除原事件；
- 每次 Attempt 引用不可变 AssessmentSubjectRef：已保存题使用 problemRevisionId/practiceUnitId/answerSpecId，未保存讲题使用 tutorAssessmentSnapshotId/answerSpecSnapshotId；
- 修改知识点绑定会让受影响的投影失效并重放；
- 每个投影保存 projectorVersion 和消费检查点；
- 普通 TutorInteraction 不是 Attempt；
- 只有 TutorAssessmentItem 为 ATTEMPT_ELIGIBLE、未先揭示答案且符合长期证据策略时，讲题会话才能调用 AttemptService。

### 9.7 模型与算法版本链

每次推理至少保存：

~~~text
InferenceRun
- inferenceRunId
- capability
- inputFingerprint
- draftVersion / problemRevisionId
- modelArtifactId
- runtimeVersion
- promptPolicyVersion
- outputSchemaVersion
- taxonomyVersion
- deviceTier
- startedAt / duration
- resultStatus
- outputHash
- verificationSummary
- userDisposition
~~~

另外分别版本化：

- TaxonomyVersion
- PromptPolicyVersion
- TutorPolicyVersion
- ReviewPlannerVersion
- LearningProjectorVersion
- RenderVersion
- BackupFormatVersion

一个模糊的 modelVersion 不能代表全部算法。

## 10. 数据所有权与唯一写入者

| 数据 | 唯一写入者 | 其他模块如何使用 |
|---|---|---|
| SourceBundle/SourceAsset | CaptureImportService/AssetVault | 模型图片理解、按需修订和讲题只读；学生不承担 OCR 数据录入 |
| RecognitionJob/Result | RecognitionOrchestrator | 合并为 Draft 建议 |
| ProblemDraft | DraftService | 按需题面修订 UI 和保存桥；正常候选直接继续 |
| Problem/Revision | ProblemCatalog | 四个根模块按 ID 查询 |
| VerifiedTeachingArtifact | TeachingArtifactCatalog | 能力门、讲题策略和评分只读 |
| ErrorBookEntry | ErrorBookService | 题库、复习、分析只读 |
| ClassificationBinding | ClassificationService | 搜索、复习、讲题只读 |
| AttemptEvent/Correction | AttemptService | 投影器顺序消费 |
| Mastery/Memory/Ability | LearningProjectionEngine | UI 和策略只读 |
| ReviewPlan/Queue | ReviewPlanner/ReviewSessionService | 复习 UI 只读和提交命令 |
| TutorSession/Turn | TutorSessionService | 讲题 UI 通过事件交互 |
| TutorAssessmentItem | TutorAssessmentService | UI 只提交选择；AttemptService 校验资格 |
| TutorGenerationRequest | TutorGenerationCoordinator | UI 观察、停止或在逻辑操作三次 dispatch 预算内经当前租约/一次明确继续恢复精确动作 |
| API/NetworkPolicy | SettingsService/CredentialVault | 所有适配器查询 |
| FTS/Analytics | 对应 Projector | 可删除、可重建 |
| Job 状态 | JobCoordinator | UI 观察、领域服务提交意图 |

跨模块只允许：

- 稳定查询接口；
- 明确命令；
- 已提交事实事件；
- 版本化共享 schema。

功能模块不能直接拿另一个模块的 DAO。

### 10.1 正式入库事务

CommitProblemDraft 是唯一提交路径：

1. 校验 draftVersion、必需字段和资产完整性；
2. 计算内容指纹并检查重复；
3. 一个事务内创建或修订 Problem、创建 ErrorBookEntry、接受用户确认的分类绑定并写 outbox；
4. 提交后异步建立 FTS、关系建议、分类补充和初始复习计划；
5. 智能任务失败不影响题目可见，只显示“智能整理待完成”。

讲题中的“存入错题本”只能调用此用例。

### 10.2 记录作答事务

1. 使用 submissionId 保证重复点击和重试幂等；
2. 在同一数据库事务内写入 AttemptSubmission、评分依据、AttemptEvent 与 projection outbox；
3. AttemptService 到此结束，不直接写 Mastery、Analytics 或 ReviewPlan；
4. LearningProjectionEngine 按 eventRange 幂等消费 outbox，在同一投影事务内更新画像和 projectionCheckpoint；
5. ReviewPlanner 只消费已提交的 projectionCheckpoint，并生成引用该检查点的计划版本；
6. 用户撤销时追加 AttemptCorrection，再走相同投影链。

UI 与 AdaptiveQuestionSelector 必须读取同一 projectionCheckpoint 的 LearnerSnapshot；投影暂时落后时继续显示“作答已保存，学习计划整理中”，不能拼接一半新画像和一半旧计划。

### 10.3 异步结果与用户编辑冲突

结构化转写、分类、关系和讲题响应必须携带 inputFingerprint、baseDraftVersion 或 problemRevisionId、模型/策略版本和 outputSchemaVersion。

结果回来时基础版本若已变化：

- 旧结果标记 STALE；
- 只能展示为“新建议”；
- 不覆盖当前用户编辑；
- 用户可对比并选择采用。

## 11. 持久异步任务系统

WorkManager 只保证满足约束后的至少一次执行，不等于完整业务队列。项目需要 Room 中的 Job 事实表、租约、检查点和幂等键，WorkManager 只负责唤醒执行器。

每个 Job、WorkRequest input、租约和结果都必须携带 generationId。Worker 在启动、领取租约和每次提交结果前读取 active-generation；不一致立即标记旧 generation 的任务为 STALE 并停止，绝不能把旧数据库/旧资产的迟到结果写入新 generation。只调用 WorkManager.cancel 不构成恢复隔离保证。

通用状态：

~~~text
QUEUED
→ RUNNING_WITH_LEASE
→ SUCCEEDED
→ PARTIAL
→ FAILED_RETRYABLE
→ FAILED_PERMANENT
→ PAUSED
→ CANCELLED
→ STALE
~~~

外部 Provider 执行还必须有独立于 requestId 的逻辑操作账本：

~~~text
logicalOperationFingerprint = hash(taskKind + immutableTypedInput)
externalDispatchCount ∈ 0..3
~~~

- 同一不可变类型输入即使因为恢复产生不同 requestId，也共享同一个 `logicalOperationFingerprint` 和三次总预算；
- 只有即将真实调用 Provider transport 的执行才在一个数据库事务中预留一次 dispatch；本地实现、Fake、Schema 校验失败和纯本地恢复都不消耗外部预算；
- 第三次预留后不得创建第四次外部发送路径。Provider 不支持幂等键时仍按“最多三次的至少一次执行”管理风险，不能用换 requestId 绕过；
- EgressManifest、Provider/策略/披露范围和逻辑输入都必须精确匹配。持久清单只证明曾获授权，不在进程重建后继续充当发送许可。

| 任务 | 幂等键 | 关键规则 |
|---|---|---|
| 外部 URI 复制 | URI 许可 + 内容哈希 | 复制完成前不释放外部来源 |
| PDF 页渲染 | 文件哈希 + 页码 + rendererVersion | 单页失败，批次继续 |
| 图像预处理 | assetHash + pipelineVersion | 新裁切生成新键 |
| 模型结构化转写/公式/手写分层 | sourceBundleHash + modelVersion + schemaVersion | 结果版本不符则 STALE |
| 分类 | revision + taxonomy + model | 只产生建议 |
| 关系发现 | revision + policyVersion | 不阻塞正式入库 |
| FTS 索引 | problemRevisionId | 可删除重建 |
| 学习投影 | projectorVersion + eventRange | 从检查点重放 |
| 每日计划 | profile + localDate + planner + stateVersion | 启动时再次校正 |
| 讲题生成 | session + clientTurn + inputFingerprint + policyVersion | 使用 TutorGenerationRequest；流式片段验证前不提交 |
| 导出 | selectionSnapshot + templateVersion | 分页断点续作 |
| 备份 | dbSnapshot + assetManifestHash | 生成后重新读取验证 |
| 模型下载 | modelId + artifactVersion | 断点、校验、原子切换 |
| 清理 | policyVersion + cutoff | 只删允许类别 |

TutorGenerationRequest 不是普通内存中的网络调用：

~~~text
QUEUED
→ REQUESTING
→ STREAMING_EPHEMERAL
→ VALIDATING
→ COMMITTED
或 FAILED_RETRYABLE / FAILED_PERMANENT / CANCELLED / STALE
~~~

它至少保存 requestId、sessionId、clientTurnId、idempotencyKey、inputFingerprint、logicalOperationFingerprint、TutorCapabilityDecision、Provider 与 EgressManifest 快照、schemaVersion、已提交 TurnId、外部 dispatch 预留次数和取消原因。停止按钮只取消当前请求，不删除已提交会话；网络流片段只能暂存在界面/临时缓冲，schema 与内容验证成功后才原子提交为 TutorTurn。

进程死亡后不能假装恢复已经丢失的流，也不能仅凭持久 EgressManifest 自动重发。页面保留已确认内容并显示平静的暂停态；只有当前进程内仍有效的租约，或学生明确点一次“继续讲题/继续对话”，才恢复暂停前的精确 `TUTOR_PLAN` 或 `TUTOR_RESPOND` 动作。该确认只授权这一个动作；不能改成“重新规划”，不能创建重复逻辑回合，也不能让首次拍题自动启动第二次。

不建设 Kafka 式通用消息总线或全量事件溯源框架。端内只需要明确 outbox、Room 状态表、WorkManager 和幂等领域命令。

## 12. 关键子系统

### 12.1 采集与文档理解

~~~text
相机/相册/PDF/分享
→ 立即复制私有临时区
→ CanonicalSourceAsset
→ 模型评估内容完整性并生成结构化转写
→ 本地 Schema/证据/安全校验
→ 可用：直接形成可修订 ProblemDraft 并继续 teach/save
→ 不可用：保留草稿，重试/重拍或补拍/换图/稍后继续
~~~

分阶段能力：

| 阶段 | 能力边界 |
|---|---|
| V1 | 单题拍照、相册、证据图保存、模型评估与结构化转写、按需修订；正常路径无人工校对关卡 |
| V1.5 | 模型辅助的多题边界/简单跨页；本地 OCR 只可作内部测试适配器，不成为产品主链或学生录入负担 |
| V2 | PDF/多图批量、跨页候选、公式识别适配器 |
| V3 | 手写分层、共用题干、复杂版面、部分图形解析 |
| 研究项 | 通用几何、电路、力学、化学结构和实验装置重建 |

ML Kit 文档扫描依赖 Google Play Services 动态下载，不可作为中国市场唯一入口；当前基础文字候选使用随 APK 打包的 `text-recognition-chinese`，并保留可替换适配器边界。默认取景仍应使用 CameraX。`strictOffline` 的真实题图采集必须在本应用进程内完成：调用隐式 `ACTION_IMAGE_CAPTURE` 会把像素交给用户设备上可处理该 Intent 的相机应用，无法继承本 APK 的无网络保证。若 pre-M1 原型暂用外部相机，界面必须明确标注“本应用不上传，但所选相机遵循其自身隐私规则”，并禁止把该入口作为真实学生数据验收通过的依据。

### 12.2 错题本、分类、去重与检索

正式题目保存后立即可见，智能分类失败不能阻塞。

基础去重优先：

1. 资产 SHA-256 和区域指纹；
2. 规范化文本/公式指纹；
3. 同 SourceBundle 的页码和坐标；
4. 用户确认的别名。

语义相似和变式关系后置到模型建议层。合并时建立 ProblemAlias，不粗暴重写所有历史外键。

千题检索目标：

- 本地 FTS 搜题干和已接受的科目/板块/知识点；
- 科目 → 板块 → 知识点稳定目录，掌握程度作为独立投影视图；
- 可搜索未归类项，但错因、来源、题型不作为分面、分组或卡片标签；
- 搜索条件持久化为最近筛选，不要求用户重复配置。

### 12.3 复习与掌握

题目记忆和知识掌握分开：

- ProblemMemoryState 负责“这道 PracticeUnit 何时可能忘”；
- KnowledgeMasteryState 负责“这个知识点的独立掌握证据如何”；
- ReviewPlanner 读取这些投影并生成可解释队列。

复习计划默认按时间预算，不强迫固定题数。积压时按遗忘风险、薄弱点、考试目标、题目成本和同题簇去重截断。SprintPlan 是临时视图，不覆盖长期排程。

生产初始化不允许写入 fixture/seed。ReviewPlanner 的候选全集来自真实保存且当前状态为 `ACTIVE` 的错题；每个 ReviewQueueItem 固定精确 `problemId + problemRevisionId`，渲染已保存的原始转写/结构化题面。模型可以解释既定计划或围绕原题讲解，但不得生成替代复习题面、伪造答案，也不得因缺少可信答案而把真实错题替换为演示题。

### 12.4 讲题

讲题编排分三层：

1. 本地可审计的当前题精确修订、相关 LearnerState 与历史事实；
2. 本地 TutorPolicyEngine 只决定数据披露、动作/GUI 白名单、答案暴露和可否形成学习证据；
3. 大模型负责理解题意、推理、讲解、换方法和当前题内按需 GUI spec，本地再做 schema/allowlist 校验与确定性渲染。

模型可以：

- 理解当前题并生成分层讲解、提示或真正不同的方法；
- 根据当前题和相关可信记忆跳过明显已会内容，从真实卡点继续；
- 只在当前题内生成有区分度的点选/排序/步骤比较等 GUI spec；
- 返回 Markdown/LaTeX 和白名单场景声明，由本地确定性渲染。

模型不能：

- 单独决定学生会不会；
- 直接改 Mastery 或 ReviewPlan；
- 未经用户明确要求生成任何新题、同类题、变式题或校准题；
- 用题库候选或额外题探测能力，或把生成题替代真实复习原题；
- 读完整数据库、API Key、文件系统或其他会话；
- 在验证失败时继续编造。

### 12.5 安全结构化渲染

优先使用原生 Compose AST 渲染，禁止 raw HTML、JavaScript、iframe、远程图片、远程字体和任意 URI 自动加载。

渲染限制：

- LaTeX 只允许白名单数学命令；
- 限制输入长度、AST 深度、节点数、公式递归和图片像素；
- 互动题是结构化 block，不是 HTML 表单；
- schema 校验失败时降级为纯文本；
- 外部链接必须用户确认后交给系统；
- 生成内容不能调用原生工具。

### 12.6 模型网关与能力路由

能力路由按任务而不是按“一个总模型”。当前产品主线是本地可信执行层 + 用户选择的外部 Provider，不以端侧模型作为语义任务的优先分支：

~~~text
任务请求
→ 本地确定性规则是否已能完成非语义工作
→ 语义任务是否需要用户所选 Provider
→ 当前内存租约/一次明确继续、EgressManifest 与三次 dispatch 预算是否有效
→ 选定 Provider 是否支持所需能力和 schema
→ 验证输出
→ 写 InferenceRun 和建议
~~~

没有 Provider、网络或额度时，本地继续支持已有数据查看、搜索、排程、修订、导出和缓存会话；新图片理解、归类和讲题进入暂停/待继续，不调用本地模板假装完成语义推理。端侧模型只可在未来独立研究项中评估，不改变当前职责边界。

每个 Provider 注册：

- 能力：文本、图像、结构化输出、流式；
- 目的地区域、保留期、训练政策；
- Base URL/模型、最大输入、超时和费用提示；
- SecretRef，不保存明文 Key；
- 健康状态和最近一次合成测试。

连接测试只发送合成数据。

### 12.7 导出与打印

导出开始时固定 ExportSelectionSnapshot：

- problemRevisionIds/practiceUnitIds；
- 排序；
- 是否含答案、解析、原图；错因、来源、题型不作为导出分类字段；
- 模板、字体、纸张和 renderVersion。

三个主要模板：

1. 练习卷：题目在前，答案单独；
2. 复盘册：题目、讲解、再练记录；
3. 周报：掌握变化、薄弱点、完成情况。

PDF、系统打印、Markdown 和长图共用同一内容模型，不通过 UI 截图拼接。复杂图无法重建时回退到对应裁剪，不能丢题。

### 12.8 备份与恢复

便携备份是应用自定义加密归档，不依赖 Android 默认云备份。

Backup Manifest 至少包含：

- backupFormatVersion；
- databaseSchemaVersion；
- appVersion；
- profileIds；
- taxonomyVersions；
- 文件清单、大小和 SHA-256；
- 加密算法与 KDF 参数；
- 创建时间；
- 是否包含原图和讲题历史。

API Key 和模型包默认不进入备份。

恢复使用 generation-based 数据根，而不是假设数据库和图片可以跨文件天然原子替换：

~~~text
app-data/
├─ generations/{generationId}/database
├─ generations/{generationId}/assets/
├─ generations/{generationId}/manifest
└─ active-generation
~~~

RestoreCoordinator 先冻结新的写命令，取消旧 generation 的 unique work，并在有界时间内等待其租约释放；即便 Worker 仍在运行，generationId 提交门也会阻止迟到写入。随后在 staging generation 解密、迁移、校验实体引用和全部哈希；关闭旧数据库，使用 fsync + 原子重命名切换 active-generation，再以新 generation 重开并做一次只读核验。旧 generation 在确认启动成功前保留用于回滚。恢复包中的 RUNNING 任务不能原样复活，只能按类型丢弃或规范化为可安全重试状态。首版只做整包替换恢复，合并两个设备数据属于同步系统。

## 13. 自适应讲题与“不会问弱智题”的契约

### 13.1 必须分开的学习状态

| 字段 | 含义 |
|---|---|
| pKnow | 相对缓慢变化的知识掌握估计 |
| pRecallAtSnapshot | 投影检查点时的独立提取概率 |
| pRecallNow | 由 pRecallAtSnapshot、recallSnapshotAt 和 halfLifeHours 在决策时派生，不单独作为事实写回 |
| pIndependentCorrectNow(item) | 将当前提取概率与题目要求、难度/辨别度、提示依赖组合后的题目级预测 |
| evidenceConfidence | 证据数量、多样性和可信度 |
| hintDependency | 是否依赖提示才能完成 |
| guessRisk/slipRisk | 猜中或粗心失误风险 |
| conflictState | 近期证据是否冲突 |
| masteryStatus | UNKNOWN/DEVELOPING/STABLE/STALE/CONFLICTED |

时间过去只会把 STABLE 变成 STALE，不能直接宣布“不会了”。

### 13.2 关键数据契约

LearnerKcState 至少包含：

~~~text
kcId
pKnow
pRecallAtSnapshot
recallSnapshotAt
halfLifeHours
evidenceMass
independentCorrectCount
independentWrongCount
distinctItemFamilyCount
distinctSessionCount
distinctDayCount
lastIndependentCorrectAt
lastIndependentWrongAt
hintDependencyEwma
guessRiskEwma
slipRiskEwma
conflictState
masteryStatus
modelVersion
stateVersion
projectionCheckpoint
~~~

统一计算契约：

~~~text
pRecallNow = clamp(
  pRecallAtSnapshot * 2^(-elapsedHours / halfLifeHours),
  priorFloor,
  pKnow
)

pIndependentCorrectNow(item) =
  calibratedResponseModel(item, requiredKcs, pRecallNow, hintDependency)
~~~

若 halfLife 或题目参数尚未校准，使用“学科 × 知识类型 × 年级”的保守先验，扩大预测区间，并以区间下界做跳过决策；UNKNOWN 不能因为点估计碰巧很高而跳过。

CurrentProblemInteraction 至少包含：

~~~text
problemId / problemRevisionId
modelTaskRequestId / responseOrdinal
currentStepId / teachingGoal
interactionKind
relatedKnowledgeNodeIds[]
optionOrStepSpec[]
allowUnknownOption
sessionOnly
verificationStatus
answerSurfaceIdentity?
~~~

每个选项或步骤都必须对应当前题中的真实思路、条件或常见卡点；没有教学区分度的选项应删除。该结构不能引用另一道题，也不能因为缺少能力数据而创建诊断/校准题。

### 13.3 证据权重初值

以下只是待校准工程初值：

| 事件 | 正确证据 | 错误证据 |
|---|---:|---:|
| 首次、独立、未看提示 | 1.00 | 1.00 |
| 只看概念方向提示 | 0.60 | 0.90 |
| 已给下一步 | 0.25 | 0.80 |
| 已给关键步骤 | 0.10 | 0.60 |
| 已揭示答案后重做 | 0.00 | 不适用 |
| 同题立即重试 | 上限 0.15 | 上限 0.40 |

必须保留 firstResponse，第二次点对不能覆盖第一次错误。

### 13.4 跳过“明显会的”规则

首版 CLEARLY_MASTERED_FOR_SKIP 同时满足：

~~~text
lowerBound(pIndependentCorrectNow) >= 0.85
AND evidenceMass >= 2.0
AND 独立答对至少 2 个不同 itemFamily
AND 证据来自至少 2 个会话或学习日
AND 没有更新的独立错误
AND 状态不是 CONFLICTED
AND 当前题目校准区间有支持
~~~

行为：

- 满足：在用户当前给出的题目内跳过已会基础，只解释迁移、边界或反例目标；
- pRecallNow 下降到约 0.75 以下：只有当前题本身存在合适推理分支时才提出一个高辨别度的本题问题，不另出复核题；
- 高掌握预测下突然错：标记 CONFLICTED 并调整当前题讲法，不生成非同构题探测能力；
- 暂无与当前题直接相关的学习记录：不显示掌握结论，也不贴“基础差”；
- 低掌握且记录充分：在当前题内进入示例或逐步淡出，不连续盘问低级问题。

0.85、0.75 和记录数只能由用户自己给出的题目与可信学习行为校准，不得通过额外出题收集。

### 13.5 选择算法

~~~text
selectNextTurn(context):
  snapshot = loadRelevantLearnerSnapshot(
      currentProblemId,
      currentProblemRevisionId,
      explicitRelatedKnowledgeNodeIds
  )

  if 题面内容不足以继续:
      return REQUEST_MINIMAL_RECAPTURE_OR_CLARIFICATION

  if 用户明确要求答案或处于核对模式:
      return EXPLAIN_CURRENT_PROBLEM_WITH_UNCERTAINTY_BOUNDARY

  hypotheses = 由当前题、用户原话、当前题内选项/步骤与相关可信记忆生成
  targets = 用户明确卡点
          → 当前题首次不成立步骤
          → 当前题关键分叉
          → 当前题直接相关且尚未稳定的知识点

  跳过 CLEARLY_MASTERED_FOR_SKIP

  if 新手状态可信且任务负荷过高:
      return CURRENT_PROBLEM_WORKED_EXAMPLE_OR_FADED_STEP

  interaction = chooseWithinCurrentProblem(
      directExplanation,
      oneUsefulHint,
      stepComparison,
      currentStepChoice,
      ordering,
      figurePointing,
      methodSwitch
  )

  reject interaction if:
      引用了另一道题
      OR 目的只是探测能力
      OR 选项太简单/无真实区分度
      OR 会泄露超出用户请求的答案

  无合适交互则直接讲当前题，不为凑 GUI 出选择题
  展示前持久化 CurrentProblemTeachingDecision
~~~

难题不等于有区分度。好的当前题交互优先处理首次不成立的步骤、条件变化、表征对应、额外条件、反例或方法选择；数据少时直接继续当前题，不设置“冷启动诊断”正确率走廊。

### 13.6 何时不用选择题

- 目标是证明、完整推导、构造、作图或建模；
- 需要主动回忆而不是看到答案后识别；
- 多个表达都可能正确；
- 无法生成三个可验证且有意义的干扰项；
- OCR/题义不确定；
- 连续靠排除法猜中；
- 连续两次选错，需要观察中间步骤；
- 用户明确不想被问或只想核对答案。

当前已实现的视觉场景白名单只有步骤流、对照表和证据链，另可显示最多三个当前题上下文动作；不适合时直接讲解。步骤排序、点选错误步骤、单行填空、拖拽配对、图中点选、符号表填格等属于后续扩展 allowlist，必须先增加本地 schema、确定性 renderer 与回归测试才能开放。纸上作答只允许拍照补充，不建设手写演算板。

### 13.7 多知识点题

- 做对可给必要知识点少量正证据，但单题不能让所有知识点直接稳定掌握；
- 做错只有在干扰项或首错步骤明确指向时才产生对应负证据；
- 若只知道整题错，标记 ambiguousMultiKc，不给全部知识点扣分；
- 只有当前题本身存在有意义的下一步分支时，才用该分支区分最可能的两个错误假设；不得另造题；
- 多种解法按学生实际策略路径更新。

### 13.8 动态动作

模型只能从动作白名单提出候选，策略引擎决定是否显示，最多展示 3 个：

- 换种表示
- 看图/符号表/受力图
- 拆成两步
- 换一种方法
- 我想自己推
- 我会这一步，跳过
- 核对题面
- 查看完整讲解

每个动作携带 actionId、labelToken、preconditions、targetState、reasonCode；不能让模型返回任意可执行命令。

### 13.9 可解释原因码

- SKIP_MASTERED_RECENT
- HANDLE_STALE_MEMORY_WITHIN_CURRENT_PROBLEM
- RESOLVE_CONFLICT_GUESS_OR_SLIP
- TARGET_CURRENT_PROBLEM_GAP
- BRIDGE_RELEVANT_PREREQUISITE_IN_CURRENT_SOLUTION
- EXPLAIN_CURRENT_BOUNDARY_CASE
- REDUCE_HINT_DEPENDENCY
- NO_RELEVANT_MEMORY_CONTINUE_CURRENT_PROBLEM
- REVIEW_DUE_TO_FORGETTING
- SWITCH_FROM_MCQ_TO_CONSTRUCTED
- SHOW_WORKED_EXAMPLE_FOR_NOVICE
- DIRECT_ANSWER_BY_USER_REQUEST
- BLOCK_UNVERIFIED_CONTENT

UI 显示自然语言，例如：

> 你最近在两道不同题里都独立完成了基础求导，所以这一步我跳过；这一问只检查导数变号与极值的关系。

### 13.10 自适应验收

- 未经用户明确要求生成的新题、同类题、变式题和校准题数量必须为 0；
- 能力证据必须全部可追溯到用户自己给出的题和可信行为；
- 缺少相关学习数据时，额外诊断题数量必须为 0，且不得贴低能力标签；
- 未验证内容产生 Mastery 更新次数必须为 0；
- 揭示答案后的同题正确被计为独立掌握次数必须为 0；
- 稳定掌握内容无意义重复率目标不高于 5%；
- 当前题内按需 GUI 必须能省略；无合适交互时直接讲解，不生成凑形式的选择题。

## 14. 数据生命周期与删除语义

### 14.1 默认保留

| 数据 | 默认策略 |
|---|---|
| CaptureRecoveryBuffer 完整画面 | 只用于中断恢复，带到期时间；用户确认裁切后删除，最长 24 小时 |
| CanonicalSourceAsset | 随题保留，作为可追溯证据 |
| 未保存讲题的脱敏题图/会话 | 7 天，可配置为 1/7/30 天或立即删除 |
| 已保存题关联讲题 | 随题保留，可单独删除 |
| 待整理 ProblemDraft | 仅在已有 CanonicalSourceAsset 后创建；不静默删除，进入待整理队列 |
| OCR 中间图/调试图 | 提交后短期，优先清理 |
| ProblemRevision/Attempt | 直到用户删除相关学习记录 |
| Mastery/Plan/Analytics | 可重建，版本升级后可全量重算 |
| 内部导出缓存 | 完成后或 24 小时 |
| 回收站 | 30 天，永久删除前说明影响 |
| 脱敏诊断日志 | 30 天，不含正文与凭证 |
| 模型包 | 用户选择和容量策略 |

### 14.2 删除动作必须区分

- 从错题本移除：归档 ErrorBookEntry，保留题目和历史；
- 移入回收站：隐藏题目和派生状态，可恢复；
- 删除题目及学习记录：永久删除关联 Attempt 并重建投影；
- 只删除讲题历史：不删除题目、Attempt，也不删除被长期 Attempt 引用的最小脱敏 TutorAssessmentSnapshot；后者随作答历史删除；
- 重新计算学习画像：保留 Attempt，只删除并重建 Mastery/Plan/Analytics 派生投影；
- 永久删除作答历史：删除 Attempt/Correction 后从剩余事实重建投影，明确不可逆；
- 重置学习偏好：只恢复提示、答案、时间预算等偏好，不碰题目和作答；
- 删除全部应用数据：由 DeleteAllCoordinator 清理 WorkManager、Job、数据库、资产、索引、缓存、分享缓存、日志、凭证、Provider/模型配置和应用私有模型包。

闪存不能承诺法证级单文件擦除。在数据库和全部资产尚未经过同一主密钥加密前，销毁 Keystore 密钥只能让 API Key/备份密钥等已加密秘密不可用，不能宣称擦除了 Room 明文。DeleteAllCoordinator 必须在重启后验证空数据库和私有目录清单；用户已通过 SAF、分享或备份导出到应用沙箱之外的文件不在应用删除能力内，界面必须明确提醒。

### 14.3 自动清理顺序

1. 渲染预览缓存；
2. 可重建 OCR 中间图；
3. 过期内部导出缓存；
4. 过期未保存讲题资产；
5. 可重新下载的非活动模型包。

永不自动删除：

- CanonicalSourceAsset；
- 正式 ProblemRevision；
- AttemptEvent；
- 用户导出的备份；
- 已有 CanonicalSourceAsset 的永久待整理草稿。

## 15. 隐私、安全与未成年人边界

本节是产品架构，不是隐私页文案。法律判断仍需在正式发布前由专业人员复核。

### 15.1 产品发行线与内部测试变体

| 变体 | Manifest | 默认能力 | 适用场景 |
|---|---|---|---|
| localFirst | 声明 INTERNET | 本地可信数据层 + 用户明确配置/授权的模型任务 | **公开产品主线** |
| strictOffline | 不声明 INTERNET | 只验证零出网、权限边界、已有数据浏览与手工作业 | 内部安全/回归测试，不作为公开模式或开发重点 |

两个变体共享数据库和领域代码。`strictOffline` 的“无出网”仍由 APK 权限硬保证，但它只是一项边界测试夹具；不得为了让它完成图片理解或任意讲题而建设第二套本地模型系统。

每次进入“智能能力”或开始讲题前读取 AppCapabilitySnapshot：

~~~text
buildVariant
networkPermissionAvailable
agePolicy
approvedProviderIds[]
arbitraryBaseUrlAllowed
installedLocalCapabilities[]
verifiedTeachingCoverage[]
selectedProviderCapability
policyVersion
~~~

- strictOffline 只用于内部诊断，不作为学生可选择的公开模式；
- 公开 V1 的未成年人同意、Provider 数据政策、家长/监护要求和地区合规需发布前专项法律评审，产品不能用一句“默认关闭远端”替代真实合规设计；
- 任意 Base URL 需要 HTTPS、主机校验、能力探测、超时、最大上传和脱敏日志；产品自有 Key 必须经过服务端网关，不能进入 APK；
- `RemoteProvider` 与 `LocalNetworkEndpoint` 是不同配置类型。局域网模型仍需要 localFirst 的 INTERNET 权限、主机白名单和明确发送预览，不能冒充端上模型；
- Provider 返回内容一律是不可信候选，不能直接写题库真值、学习事实或掌握度。

### 15.2 数据敏感度

需要高敏感级保护的数据包括：

- 原始卷面中的姓名、学校、班级、考号、人脸、二维码和教师批注；
- OCR 后的上述文本；
- 掌握度、薄弱点、错误模式、作答速度和提示依赖；
- 讲题自由输入和历史；
- 缩略图、裁剪、向量、标签、计划等派生数据；
- API Key、服务商地址、备份口令；
- 模型运行与网络活动元数据。

不满十四周岁的个人信息依法属于敏感个人信息。首次启动只询问年龄段：

- 不满 14 岁；
- 14–17 岁；
- 18 岁以上；
- 暂不提供，按最严格未成年人默认值运行。

不收集身份证、真实姓名、学校、学籍、家长职业或人脸来做年龄证明。

### 15.3 产品范围：讲题，不做情感陪伴

可以有聊天界面、连续追问和鼓励，但角色始终是“AI 讲题助手”：

- 不扮演同学、亲属、恋人或全天候朋友；
- 不建设亲密关系、情绪依赖、深夜召回和虚拟陪伴；
- 非学习型心理危机只显示非诊断性求助提示和可信成人建议；
- 不用断签羞辱、惩罚倒计时、抽奖或排行榜诱导沉迷。

这避免项目膨胀成另一套未成年人情感陪伴安全平台。

### 15.4 信任边界与安全不变量

主要信任边界：

1. 相机/相册到临时资产区；
2. 资产/OCR 到本地或远端模型；
3. 学习数据库到最小 MasterySnapshot；
4. 模型输出到结构化渲染器；
5. 私有存储到分享、导出和备份；
6. App 到自定义服务商；
7. 模型包和依赖到可执行环境。

长期不变量：

- 除统一 NetworkGateway 外，模块不得直接联网；
- 模型不能访问数据库、文件系统、API Key 或 Android 工具；
- 模型只接收最小 TutorInput，不接收完整历史；
- 输出只成为验证后的展示块或建议；
- Markdown/LaTeX 不执行脚本、不加载远程资源；
- API Key 不进入日志、备份、导出、通知、剪贴板预览或模型提示；
- 存题、删除、导出和分享必须由用户明确操作。

### 15.5 本地数据保险库

基础保护：

- 所有业务数据放 App 私有存储；
- 设备文件级加密与 Android 沙箱作为基础边界；
- API Key 与主密钥引用使用 Android Keystore；
- 文件和便携备份使用经审计的 AEAD；
- 密钥失效时要求重新输入，不回退成明文；
- API Key 页、备份口令页和危险操作前支持设备重新认证。

数据库全库加密与 Room 3/FTS 的兼容性需要独立技术验证。首版不能为了宣称“加密”引入未经验证的数据库驱动；威胁模型、性能和迁移测试通过后再决定 SQLCipher 或字段级加密。无论是否全库加密，strictOffline、私有存储、设备锁和最小导出仍是硬要求。

### 15.6 拍照隐私预处理

进入远端模型前必须完成：

- 自动裁边、旋转、透视校正；
- EXIF/GPS/设备信息剥离；
- 疑似姓名栏、人脸、二维码、条形码提示；
- 手工裁切和涂抹；
- 在需要发送确认时，以学生语言展示实际将发送的裁剪图和结构化题面摘要，不显示 OCR/流水线术语。

二维码只作为图像内容，不自动打开。

### 15.7 提示注入隔离

照片、OCR、EXIF、二维码和导入文档全部是不可信数据：

- 控制指令和题目数据分字段传递，不做字符串拼接；
- “忽略之前指令”等题面文字只能当学习内容；
- 不给模型浏览器、Shell、SQL、文件、分享或删除工具；
- 输出只能进入白名单 schema；
- 写操作仍需领域命令与用户动作；
- 安全验证失败的互动不能生成 Attempt。

### 15.8 模型与 OCR 供应链

本节的“模型包”仅适用于未来独立研究或内部工具；当前公开产品不下载端侧大模型、不用本地 OCR/模型承担图片理解或讲题，也不得据此扩大当前实现范围。当前主线的供应链重点是 Provider 适配器、图片解码、Schema/渲染器和构建依赖。

模型包只接受白名单静态格式：

- 签名 manifest、SHA-256、来源、许可证和运行时要求；
- 隔离暂存、验证后原子切换；
- 保留最近可用版本用于回滚；
- 拒绝 pickle、脚本、动态插件和未知原生代码；
- 限制文件数、总大小、解压比和路径，防止 zip bomb/路径穿越；
- 对 tokenizer、图片解码和模型解析器做畸形输入测试；
- 维护软件 SBOM 与模型 BOM。

开发与发布环境同样属于供应链边界：

- `tools`、`.toolchains` 与 `.android` 只允许当前开发用户、SYSTEM 和 Administrators 修改；共享电脑或弱 ACL 时禁止处理真实学生图片和真实 API Key；
- 启动脚本只能做 fail-closed 权限检查，不能把“脚本自检”当信任根；宿主 ACL 必须由受信任管理员在脚本外设置；
- ADB 私钥和模拟器 console token 不长期归档，旧副本需轮换并按明确保留策略清理；
- Gradle wrapper 固定发行包 SHA-256，依赖锁、校验元数据、SBOM 和许可证报告进入 CI；
- debug APK 只用于模拟器夹具验收；正式交付必须是独立签名的 non-debuggable release，并验证签名、权限、备份规则和网络出口。

### 15.9 网络出口策略

每次远端请求先生成 EgressManifest：

~~~text
recipientName
destinationHost
processingRegion
dataCategories
purpose
retentionPolicy
trainingPolicy
minor/sensitive flags
consentVersion
payloadPreviewHash
~~~

网络规则：

- 仅 HTTPS，TLS 错误硬失败；
- 跨主机重定向不得携带 Authorization；
- Base URL 规范化并校验最终 host；
- 连接测试只发送合成数据；
- `EgressManifest` 持久化后只作为审计证据；应用或进程重建后不能单独授权发送，必须有当前内存租约或学生对精确动作的一次明确“继续”；
- 同一逻辑操作跨 requestId 最多三次真实 Provider dispatch，本地执行不消费该预算；
- Key 只在最终 host 校验后附加；
- localhost 可作为本机模型服务例外；
- 局域网明文端点默认拒绝；
- 服务商地区、保留或训练政策未知时不发送真实题目。

未成年人海外模型、自定义任意 Base URL 和数据出境不作为普通设置。公开 V1 对不满 14 岁锁定远端能力；其他未成年人也默认本地，任何开放都必须先完成影响评估、服务商审查和同意机制。

### 15.10 权限

V1 只需要：

- CAMERA：用户点击拍题时申请；
- POST_NOTIFICATIONS：用户主动开启提醒时申请；
- INTERNET：仅 localFirst 变体。

不需要：

- 广泛相册/存储权限，使用 Photo Picker 和 SAF；
- 位置、通讯录、电话、短信、设备标识、应用列表；
- 麦克风、日历、辅助功能、悬浮窗；
- 精确闹钟和全屏通知。

拒绝相机后仍能选图或导入文件；不得把手工录题包装成相机/OCR 失败退路。拒绝通知后计划仍完整可用。

### 15.11 Android 默认备份

strictOffline 显式关闭 Auto Backup，并同时配置旧版 backup rules 与 Android 12+ data extraction rules，排除：

- 数据库；
- 照片和派生图；
- API Key；
- 聊天和模型上下文；
- 网络记录；
- 设备到设备传输。

换机依赖用户主动创建的加密备份。没有备份时，设备丢失后的数据不可恢复，产品必须提前说明。

### 15.12 截屏、日志、剪贴板和分享

截屏：

- API Key、备份口令和高风险设置使用 FLAG_SECURE；
- 最近任务缩略图显示中性占位；
- 普通讲题页允许学习截图，但提供“生成脱敏分享图”。

日志：

- Release 不记录题面、OCR、路径、URI、Key、请求体或响应体；
- 凭证类型的 toString 永不泄露；
- 只保存错误码、本地随机请求 ID、耗时和能力版本；
- 诊断默认关闭，生成前预览。

剪贴板：

- 不自动读取；
- Key 只允许用户主动粘贴；
- 敏感复制使用系统敏感标记并尽快清除。

分享：

- FileProvider 使用 content URI、exported=false 和短期只读授权；
- 只暴露专用 share-cache；
- 分享前脱敏预览；
- 文件名/PDF 元数据不含姓名、学校和薄弱点；
- AI 讲解导出保留“AI 生成，可能有误”标识。

### 15.13 隐私 UI

首次启动：

- 真实说明哪些能力在本机；
- 年龄段选择；
- 默认不开云、诊断和通知；
- 说明临时图和会话保留期限。

拍照页：

- 常驻“仅本机处理”或“将发送至某服务商”；
- 支持裁剪、涂抹、删除完整画面；
- 提醒疑似身份字段；
- 云请求前显示 payload。

讲题页：

- 明示 AI 生成与当前 Provider；
- 有“为什么问这题”；
- 不伪装真人老师；
- 模型错误可反馈、纠正和回到原题。

我的 → 数据与隐私：

- 当前模式与 APK 能力；
- 本机数据清单、占用和保留期；
- 最近网络活动与发送字段；
- 服务商地区、保留和训练政策；
- 权限、应用锁、截屏和锁屏通知；
- 学习标签查看、纠正和重置；
- 导出、恢复、删除和清空全部。

## 16. 前端页面、状态与组件

### 16.1 视觉基线

继续使用用户选择的方向 1：

- 暖白纸色背景；
- 深墨蓝/黑正文；
- 克制的玉石绿为主动作和积极状态；
- 橙红只用于待复习、警告和危险；
- 细线分隔、中等圆角、低阴影；
- 题面和公式优先，装饰不抢内容；
- 图标使用 Android Material 图标库或真实生成资产，不用 emoji、字符画或手工伪 SVG。

现有四张根页视觉稿是视觉真相，但不是完整页面清单。

### 16.2 四个根页

复习根页的区域顺序：

1. 日期、模式和中断任务；
2. 今日时间预算/到期量；
3. 开始复习主按钮；
4. 为什么推荐与薄弱点；
5. 冲刺/历史二级入口；
6. 录入错题快捷动作；
7. 固定底栏。

讲题根页：

1. 当前题/继续上次；
2. 已跳过内容与“为什么”；
3. 题面；
4. 教学对话和互动 block；
5. 最多 3 个动态动作；
6. 输入框、拍题讲解、停止/发送；
7. 显式存入错题本；
8. 固定底栏。

错题本根页：

1. 真实错题数；仅有未完成草稿时显示平静的待处理入口；
2. “拍照或上传错题”主动作；
3. 搜索；
4. 科目 → 板块 → 知识点目录；
5. 独立的掌握程度视图/筛选；
6. 题目列表和状态；
7. 批量/回收站/导出二级入口；
8. 固定底栏。错题总目录为空显示“还没有错题”，筛选为空使用独立筛选空状态。

我的根页：

1. 本地学习档案状态；
2. 本周学习与趋势；
3. 当前薄弱点；
4. 学习目标；
5. 智能能力（仅在 AppCapabilitySnapshot 允许时出现 API 配置）；
6. 数据与隐私；
7. 提醒和使用偏好；
8. 存储、备份与导出；
9. 固定底栏。

### 16.3 必须设计和实现的二级页

| 页面 | 关键状态 |
|---|---|
| 复习会话 | 未揭晓、作答中、提示后、已提交、讲题往返、撤销结果 |
| 复习结果 | 为什么改期、下一题、结束、冲刺不改长期计划 |
| 相机/相册 | 权限拒绝、低存储、连续拍、意图提示；内容问题只呈现模型给出的最小可行动结果，不展示质量分数/流水线 |
| 裁切/隐私 | 多题框选、跨页关联、姓名涂抹、实际出网预览 |
| 按需题面修订 | 仅由学生主动进入；原图联动、当前修订、用户编辑锁和保存/取消，不显示 OCR 置信度或内部阶段 |
| 批量导入 | 页面进度、部分失败、暂停、恢复、容量预检 |
| 题目详情 | 当前/历史修订、原图证据、科目/板块/知识点、独立掌握变化、作答历史、重做、讲题 |
| 讲题历史 | 会话列表、过期临时题、删除、从原任务继续 |
| 讲题能力不可用 | Provider/网络/额度不可用时保留当前题、缓存内容与精确暂停动作；缺少可信答案只禁自动评分/掌握写入，不禁模型讲解当前题 |
| 讲题请求恢复 | 精确暂停动作、Provider/策略/披露范围、当前租约/一次明确“继续”、三次 dispatch 上限；不重复首次启动 |
| 搜索/分面 | 最近条件、筛选无结果、科目/板块/知识点、独立掌握程度、待归类；无错因/来源/题型 |
| 重复处理 | 同题、新错误、附加来源、修订、变式、独立保存 |
| 导出 | 选择快照、模板、答案策略、进度、失败项 |
| 智能能力/API | 按 AppCapabilitySnapshot 显示；Provider、测试、Key、能力与地区 |
| Model Gateway / Provider | 能力兼容、Schema、流式、超时、限流、费用、数据政策、切换回滚 |
| 隐私 | 模式、网络活动、权限、保留、画像重置、删除 |
| 备份恢复 | 空间、密码、校验、迁移、预览、回滚 |

### 16.4 跨页共用状态组件

- RecoveryBanner：继续未完成的草稿/复习/讲题/导出；
- ProcessingChip：只在确有等待且信息对下一步有用时显示用户语言；不得展示 OCR/Schema/队列内部阶段；
- LocalityBadge：仅本机或具体 Provider；
- OnDemandRevision：学生主动指出题面有误时才显示原图对照和修订；不设置强制确认；
- AdaptiveReasonSheet：为什么跳过、为什么问这题；
- SafeAnswerReveal：提示层级、精确答案表面身份及“底部完整进入真实视口后才曝光”的本地观测；
- UndoResultSnackbar：短时撤销误记作答；
- StorageImpactSheet：哪些可清理、会失去什么；
- EgressPreview：发送对象、字段、地区、保留；
- EmptyState：区分无题、今天完成、无到期、无结果、无能力；
- FailureRecovery：原因、已保存内容、下一可行动作。

### 16.5 无障碍

- 所有触控目标至少 48dp；
- 状态不只靠颜色；
- 200% 字体仍能访问主动作；
- 公式和题图可独立缩放；
- 选择题提供语义组、选中状态和提交状态；
- 图标都有内容描述，装饰图不重复朗读；
- 键盘/读屏可完成核心流程；
- 横屏和分屏时底栏与主动作不消失；
- 单手高频动作位于屏幕下半部。

## 17. Android 技术与物理模块

### 17.1 当前工程版本与决策

| 组件 | 版本/决策 |
|---|---|
| Android Gradle Plugin | 9.3.0 |
| 项目 Gradle Wrapper | 9.6.1 |
| Gradle 运行 JDK | 21 |
| 编译字节码 | Java 17 |
| Kotlin/Compose compiler plugin | 2.3.21 |
| Compose BOM | 2026.06.00 |
| compileSdk/targetSdk/minSdk | 37/36/23 |
| Activity Compose | 1.13.0 |
| Lifecycle | 2.11.0 |
| Navigation Compose | 2.9.8 |
| CameraX | 1.6.1 |
| WorkManager | 2.11.2 |
| Room | 3.0.0，垂直切片部分完成（不等于 M1 通过） |
| SQLite framework | 2.7.0 |
| KSP | 2.3.10 |
| Hilt | 2.60.1，可选，真正需要时再启用 |

AGP 9.3 需要 Gradle 9.5.0+；工程当前 wrapper 固定为 9.6.1。AGP 9 使用内置 Kotlin，Android 模块不再应用旧的 org.jetbrains.kotlin.android 插件。

Room 3 runtime 与当前 schema v17 已在 `core:database`、`core:data` 和共享 `StudyExperienceRepository` 形成学习账本、单题采集、模型任务、精确答案曝光和逻辑外部操作预算的部分垂直切片。2026-07-22 最终同轮证据以 [`scenario-registry.md`](scenario-registry.md) 第 4.1 节为准：API 36 全工程设备 XML 共 `283` 项、`0` 失败、`0` 错误、`6` 项仅在 strictOffline 边界下预期跳过；本轮实际刷新生成的 JVM XML 共 `495` 项且全部通过；双 flavor Lint 为 `0 error`，两个 APK 均重新生成。复习提交与队列推进同属一个 Room 事务；讲题/复习/采集失败重试复用首次完整命令；外部模型任务跨请求共享最多三次真实 dispatch，本地执行不消耗预算；采集草稿按 basis revision 拒绝迟到结果；图片在打开前接受完整请求体预算预检；持久 manifest 只能作为证据，重建后外部整理或讲题动作都要凭当前租约或一次精确“继续”。这仍只证明受控垂直切片，不证明 M1 或 M2 退出门禁通过；真实 Provider 教学质量、备份、物理设备强杀、复杂手写/公式/图形识别和全持久点故障注入仍按合同执行。

### 17.2 物理 Gradle 模块

逻辑模块很多，但首批不把每个 service 都拆成 Gradle 模块。建议：

~~~text
:app
:core:model
:core:domain
:core:database
:core:data
:core:ui
:feature:capture
:feature:review
:feature:tutor
:feature:library
:feature:profile
~~~

职责：

| 模块 | 内容 |
|---|---|
| app | Application、导航组合、依赖装配、flavor manifest |
| core:model | 纯 Kotlin ID、实体、事件、schema |
| core:domain | 纯 Kotlin 用例、策略、状态机、端口 |
| core:database | Room entity/DAO/migration/FTS，不向外暴露 entity |
| core:data | Repository、AssetVault、Job、Settings、Provider adapter |
| core:ui | 主题、token、图标、共享安全 renderer 和组件 |
| feature:capture | 相机、导入、证据预览、按需题面修订 UI；正常路径无 OCR 校对关卡 |
| feature:review | 复习根页、会话、结果、冲刺 |
| feature:tutor | 讲题根页、互动 block、会话历史 |
| feature:library | 错题本、搜索、详情、分类、导出入口 |
| feature:profile | 我的、学习数据、API、隐私、存储与备份 |

依赖方向：

~~~text
feature:* → core:domain + core:model + core:ui
core:data → core:domain + core:model + core:database
core:database → core:model（仅映射边界）
app → feature:* + core:data
~~~

feature 之间不互相依赖。讲题保存、复习求助等跨域动作通过 app 注入的用例和稳定路由完成。

### 17.3 平台决策

- UI：Jetpack Compose + 单向数据流；
- 屏幕状态：ViewModel/StateFlow，进程恢复只保存 workflow ID 和最小输入；
- 权威业务状态：Room/文件，不依赖 ViewModel 存活；
- 异步：Coroutine + Room Job + WorkManager；
- 图片输入：CameraX、Photo Picker、SAF；
- 搜索：Room FTS5 技术尖峰，保留退路；
- 富文本：原生结构化 Compose renderer，不用 WebView；
- 密钥：Android Keystore；
- 打印：Android Print Framework；
- 网络：单一 Gateway + Network Security Config；
- Model Gateway：先定义按任务拆分的 CapabilityPort 与 Fake Provider，再接真实 Provider，避免页面直接绑定具体 SDK。

## 18. 验证体系

### 18.1 领域与数据

- 状态机表驱动测试；
- 幂等命令重复执行测试；
- AttemptCorrection 与投影重放；
- Attempt 提交后、投影事务前/中/后崩溃，按 checkpoint 重放且不重复计分；
- AdaptiveSelector 读取同一 projectionCheckpoint 的一致快照；
- 题目修订后旧推理结果 STALE；
- 多知识点题不“一错全扣”；
- 基于 fake clock 的时区、跨日和时间回拨；
- Room migration 与旧备份恢复样本；
- Asset 哈希、引用计数和孤儿清理；
- 备份篡改、截断、错密码、generation 指针切换和原子回滚。

### 18.2 自适应与教学

- 新用户不贴低能力标签；
- 两题族独立正确后跳过基础；
- 证据过期只在用户当前题存在合适分支时调整讲法，不另出校准题；
- 高掌握突然错误只形成冲突状态并调整当前题讲法，不生成验证题；
- 提示后正确和看答案后正确不算独立掌握；
- 没有 VerifiedTeachingArtifact 时仍可模型讲解当前题，但禁止自动评分和掌握更新；
- TutorAssessmentItem 的 SESSION_ONLY、BLOCKED 与 ATTEMPT_ELIGIBLE 三种路径分别验证；
- 展示后先点提示/完整答案再提交、提交与揭示并发、选中未提交再修改，均由 eventSequence 重算 AssessmentSubmissionContext；
- 展示意图写入失败时不展示答案；意图已写但精确答案表面底部尚未完整进入视口即崩溃时仍按未暴露恢复；
- 同回合多条回复按 `modelTaskRequestId + surfaceKind + responseOrdinal` 隔离，另一条回复可见不能替代当前答案表面的暴露证据；
- 未保存错题但选择 LONG_TERM 的可信评测，只保留脱敏评测快照且不生成 ErrorBookEntry；
- 无可信干扰项时切换交互；
- 多正确选项在展示前被拦截；
- 生成 JSON/schema 错误保留当前题与会话并进入可继续状态，不回退本地模板冒充语义讲题；
- 答案与求解器/权威来源冲突时阻断；
- 未经用户明确请求生成新题/同类题/变式题/校准题的次数为 0。

### 18.3 Android 生命周期与故障

- 飞行模式跑通核心闭环；
- 相机/通知拒权；
- 低存储、低内存、低电和发热降级；
- 在每个持久检查点强杀进程并恢复；
- 强杀/重建后仅凭持久 EgressManifest 不触发网络；当前内存租约或一次明确“继续”恢复精确动作，且首次自动启动不重复；
- 同一逻辑外部操作跨 requestId 的 Provider dispatch 总数不超过 3，本地执行不消费预算；
- PDF 某页失败时其余页面继续；
- 导出和备份中断后续作；
- 横屏、分屏、大字号、读屏；
- 锁屏/来电后计时恢复；流式生成只恢复已提交 Turn，暂停态只给一个明确“继续”来恢复精确动作；“重新生成”必须是学生另行明确发起的新逻辑操作，不能伪装成恢复或绕过三次 dispatch 上限。

### 18.4 安全

- 解包 strictOffline APK，确认无 INTERNET 权限；
- 抓包确认页面和后台任务零出站；
- 依赖清单无广告、分析、远程配置和推送 SDK；
- Markdown 远程图、脚本、iframe、深嵌套和宏递归；
- 图片/OCR/EXIF 中的提示注入；
- API Key 不出现在 logcat、备份、分享、通知和诊断包；
- FileProvider 路径、URI 权限和分享缓存；
- 模型包哈希、签名、zip bomb 和路径穿越；
- 删除题目后的所有派生物；
- DeleteAllCoordinator 完成后重启为空库，私有目录清单只剩允许的安装级文件；
- 永久删除题目/作答或清空全部后，不保留包含原 ID、题面、画像或用户关联的审计记录；
- 明确验证外部 SAF/分享/备份文件不会被虚假声称已删除；
- strictOffline、未成年人、成人高级版三种 AppCapabilitySnapshot 不出现越权设置。

### 18.5 UI 与 Product Design

每个关键页面都要：

1. 在真实模拟器按目标 viewport 截图；
2. 与对应视觉稿放到同一对比图；
3. 检查字体、间距、颜色、资产、文案和可交互状态；
4. 修复全部 P0/P1/P2；
5. 在 `docs/history/` 保存 design-qa.md，final result 必须是 passed 才能交付。

除根页外，至少验证录入直接继续、学生主动题面修订、复习未揭晓、复习结果、讲题选择、讲题能力不可用、精确请求恢复、会话历史、错误恢复和按能力动态显示的 API/隐私设置。

## 19. 分期路线与退出条件

本节保留 Phase 0–4 作为历史索引，不再把它们当成并行验收体系。canonical 映射是 Phase 0 = pre-M1、Phase 1 = M1；旧 Phase 2 拆入 M2/M3/M4，旧 Phase 3 进入研究阶段 R，旧 Phase 4 拆入 M3/M4/R。里程碑退出门禁只引用 M1 合同；完整映射见 [`scenario-registry.md`](scenario-registry.md#3-phase-04-与-m1m4-映射)。

### Phase 0（= pre-M1）：契约、安全与可运行骨架

这一阶段只验证架构和 UI，不冒充已经支持任意题讲解。

交付：

- 核心 ID、命令、状态机、写入者和错误类型；
- strictOffline/localFirst flavor 与 AppCapabilitySnapshot；
- 四栏 Compose 根页、真实导航和返回恢复参数；
- Structured Renderer、仅测试/显式演示环境可加载的人工验证 fixture；生产初始化路径不可引用；
- TutorCapabilityGate、TutorAssessmentItem 和规则选择器接口；
- fake clock、fake provider、模拟本地数据和进程恢复骨架。

退出条件：

- 单元测试和构建通过；
- 模拟器可在显式测试环境完成四栏导航、fixture 讲题和设置返回路径；生产构建新装后必须保持真实空库；
- strictOffline APK 无 INTERNET；
- 没有 VerifiedTeachingArtifact 时，UI 仍可由模型讲解当前题，但不得显示自动评分或掌握更新承诺；没有 Provider 时才进入可继续的暂停态，不用本地模板冒充；
- 视觉稿与模拟器同屏对照无 P0/P1/P2。

### Phase 1（= M1）：可信学习记忆与复习底座

只做一条从真实保存题目到再次学习都可追溯的链，先证明数据与教学证据正确；真实采集、CanonicalSourceAsset 和生产 `CommitProblemDraft` 归 M2。fixture 只用于测试，不得进入生产初始化：

~~~text
测试环境：显式 curated fixture
生产环境：用户真实保存且 ACTIVE 的精确 ProblemRevision
→ Problem + ProblemRevision + PracticeUnit + ErrorBookEntry
→ 错题本详情
→ 有可信 AnswerSpec/VerifiedTeachingArtifact 的一次可评分作答
→ Attempt + outbox
→ Mastery 投影
→ ReviewPlan
→ 复习会话与学习数据
~~~

交付：

- Room 3 权威数据源、Problem/Revision/PracticeUnit、Attempt/outbox、投影、ReviewPlan/Session 和共享 Repository；当前 curated 切片已经过 JVM、Android 16、双 flavor 构建/Lint 与根体验复验，但整体仍为**部分完成**，门禁未通过；
- 测试 APK 可显式加载人工验证教学样本；生产 APK 不自动写入样本、演示题或 ReviewPlan；
- 所有真实 `ACTIVE` 错题都可按规则进入复习并展示已保存原始转写；可信答案只决定能否自动评分/写掌握证据，不决定能否让模型解释当前题；
- 白名单 Structured Renderer，以及恶意/未知内容的安全降级；
- 四根页读取同一已提交 Repository/投影检查点，不显示静态题数、固定到期量或伪趋势；
- Attempt、投影、复习会话的幂等、重放、故障恢复和明确失败状态；
- 无模型时准确显示当前能力边界并保存可续跑任务，不把手工/fixture 能力包装成智能讲题。

退出条件：

- 在内部离线回归夹具中，上述已实现的确定性垂直切片成功率 100%；该结果只证明本地数据可靠性，不代表公开产品无需模型；
- 在每个持久检查点强杀进程后，作答、投影、计划和复习位置恢复率 100%；
- 未验证内容、SESSION_ONLY 互动和答案揭示后的重做产生 Mastery 更新次数均为 0；
- Attempt 提交后投影失败可重放，不出现半新画像/半旧计划；
- 重复提交、时间回拨、时区切换、修正后全量重放和原子会话推进分别通过测试；
- 单元测试、数据库测试、双 flavor 构建与 lint 通过。

### 旧 Phase 2（已退役）：拆分为 M2、M3、M4

旧 Phase 2 把采集、Provider、批处理、导出和备份混在同一退出条件中，现按责任拆分：

| canonical 里程碑 | 从旧 Phase 2 接收的内容 | 退出证据归属 |
|---|---|---|
| **M2：采集与目录** | CameraX/Photo Picker、CanonicalSourceAsset、模型图片评估与结构化转写、按需修订、FTS、科目→板块→知识点归类、重复/修订 | 录入时长、不可继续时重拍/补拍/换图、进程恢复、迟到结果不覆盖、8–12 名分层研究；不得要求手录/OCR 确认 |
| **M3：Provider / 当前题教学 / 批处理** | 任务型 Model Gateway、当前题内动态动作、相关记忆披露、受控 Provider/BYOK、PDF/多图页级任务与部分失败恢复 | 未验证内容零掌握写入、Provider 故障恢复、精确动作续跑、每逻辑操作最多三次 dispatch、答案底部可见性、“为什么这样讲”理解率 |
| **M4：导出 / 备份 / 韧性** | PDF/Markdown/系统打印、加密便携备份恢复、迁移、回收站/删除、低存储/低内存/发热/无障碍发布矩阵 | active generation/引用/哈希一致、删除/迁移不破坏数据、导出失败隔离与设备矩阵 |

不得再使用“Phase 2 完成”作为任何验收结论；必须分别引用 M2、M3 或 M4 门禁。

### 旧 Phase 3（= 研究阶段 R）：高成本智能增强

候选：

- 独立端侧 HWR、公式和复杂版面识别系统；
- 受限学科图形解析；
- 全学科知识体系和教材版本；
- 相似题关系和迁移题库；
- 面向用户的本地大模型设备分级与离线智能产品线；
- PFA/个体化学习速率与长期校准。

每项单独立项，有金标数据和退出门槛；不能打包承诺“全科全自动”，也不能因编号相近而写成 M3 交付。

### 旧 Phase 4（已退役）：拆入 M3、M4 与 R

| 历史内容 | canonical 去向 |
|---|---|
| 经 AppCapabilitySnapshot、Gateway/EgressManifest 约束的 Provider/BYOK | M3 |
| 本地加密备份恢复、迁移、导出与设备韧性 | M4 |
| 端到端加密同步、账号与设备冲突 | 研究阶段 R |
| 重型 OCR/VLM、模型评测和发布平台 | 分别按 M3 受控适配器或研究阶段 R 立项，不能笼统归入 M4 |

老师端、家长端和多人档案仍需独立产品立项，不属于 M1–M4。

## 20. 关键风险和待冻结决策

| 决策 | 当前建议 | 冻结时点 |
|---|---|---|
| 未保存讲题题图期限 | 脱敏题图 7 天；完整画面最长 24 小时 | pre-M1 合同；M2 实现 |
| 已保存原图 | 保存脱敏裁切后的 CanonicalSourceAsset | pre-M1 合同；M2 实现 |
| 默认答案策略 | 有 VerifiedTeachingArtifact 时先提示；用户明确请求可一键完整讲解；无可信材料则不伪造 | 已冻结 |
| 首批可信评分材料 | fixture 仅用于测试/显式演示；生产初始化无 seed。教师/来源答案与受支持规则只控制自动评分，所有真实 ACTIVE 错题仍按原始转写复习 | 已冻结 |
| 未保存讲题的学习证据 | 默认区分错题本保存与 LONG_TERM/SESSION_ONLY；长期证据只留最小脱敏评测快照 | pre-M1 契约、M1 行为 |
| 学习数据重置 | 重算投影、删作答历史、重置偏好三个命令分开 | 已冻结 |
| 备份原子恢复 | generation 根目录 + active 指针，旧 generation 延迟清理 | M4 |
| 复习算法 | 端口 + 可解释基线；FSRS 需与参考实现做金标一致性 | M1 基线；研究阶段校准 |
| 知识掌握 | 加权 BKT/遗忘基线，版本化可替换 | M1 基线；研究阶段校准 |
| Room 3 | curated 垂直切片部分完成；完整门禁未过，兼容阻塞时可回退 2.8.4 | M1 |
| 数据库全库加密 | 先威胁/兼容/性能验证，不作空口承诺 | M4 发布前 |
| 不满 14 岁远端模型 | V1 禁止 | 已冻结 |
| 任意 Base URL | 仅成人高级/开发者 localFirst；未成年人不可见 | 已冻结 |
| PDF 批量导入 | Beta，不是首个可运行切片 | 已冻结 |
| 通用手写/理科图 | 独立研究系统，非 MVP 承诺 | 已冻结 |
| 多学生/账号/同步 | 后置 XL 系统 | 已冻结 |

## 21. 主要研究依据

Android 与平台：

- [Android 离线优先架构](https://developer.android.com/topic/architecture/data-layer/offline-first)
- [Android 数据层](https://developer.android.com/topic/architecture/data-layer)
- [Compose UI 状态与进程恢复](https://developer.android.com/topic/architecture/ui-layer/stateholders)
- [保存 UI 状态](https://developer.android.com/topic/libraries/architecture/saving-states)
- [WorkManager](https://developer.android.com/reference/androidx/work/WorkManager.html)
- [Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker)
- [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
- [Android Auto Backup](https://developer.android.com/identity/data/autobackup)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Android 网络安全配置](https://developer.android.com/privacy-and-security/security-config)
- [Android 安全文件分享](https://developer.android.com/training/secure-file-sharing)
- [ML Kit 中文文字识别](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [ML Kit 文档扫描](https://developers.google.com/ml-kit/vision/doc-scanner/android)
- [Android AI/ML 方案选择](https://developer.android.com/ai/overview)
- [AGP 9.3 发布说明](https://developer.android.com/build/releases/agp-9-3-0-release-notes)
- [AGP 内置 Kotlin 迁移](https://developer.android.com/build/migrate-to-built-in-kotlin)
- [Compose BOM](https://developer.android.com/develop/ui/compose/bom)
- [Room 3.0](https://developer.android.com/jetpack/androidx/releases/room3)

学习科学与自适应：

- [Bayesian Knowledge Tracing](https://doi.org/10.1007/BF01099821)
- [Performance Factors Analysis](https://files.eric.ed.gov/fulltext/ED506305.pdf)
- [Individualized Bayesian Knowledge Tracing](https://www.cs.cmu.edu/~ggordon/yudelson-koedinger-gordon-individualized-bayesian-knowledge-tracing.pdf)
- [Contextual Guess and Slip](https://www.cs.cmu.edu/afs/cs/Web/People/rsbaker/BCA2008V.pdf)
- [Half-Life Regression](https://aclanthology.org/P16-1174/)
- [Personalized Spacing](https://doi.org/10.1177/0956797613504302)
- [Computerized Adaptive Testing / IRT](https://iacat.org/introduction-to-cat/)
- [Q-matrix validation](https://doi.org/10.1111/j.1745-3984.2008.00069.x)
- [Multiple-choice distractors and memory](https://psychnet.wustl.edu/memory/wp-content/uploads/2018/04/Butler-Roediger-2008_MemCog.pdf)
- [National Academies assessment triangle](https://www.nationalacademies.org/read/10019/chapter/4)
- [IES learning practice guide](https://ies.ed.gov/ncee/WWC/Docs/PracticeGuide/20072004.pdf)
- [NIST Generative AI Risk Profile](https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.600-1.pdf)
- [Anki FSRS 说明](https://docs.ankiweb.net/deck-options)
- [FSRS Kotlin landscape](https://github.com/open-spaced-repetition/awesome-fsrs)

隐私、安全与未成年人：

- [个人信息保护法](https://www.cac.gov.cn/2021-08/20/c_1631050028355286.htm)
- [未成年人网络保护条例](https://www.cac.gov.cn/2023-10/24/c_1699806932316206.htm)
- [促进和规范数据跨境流动规定](https://www.cac.gov.cn/2024-03/22/c_1712776611775634.htm)
- [人工智能拟人化互动服务管理暂行办法](https://www.cac.gov.cn/2026-04/10/c_1777558395078289.htm)
- [OWASP Prompt Injection](https://genai.owasp.org/llmrisk/llm01-prompt-injection/)
- [OWASP LLM Supply Chain](https://genai.owasp.org/llmrisk/llm032025-supply-chain/)
- [Gradle Dependency Verification](https://docs.gradle.org/current/userguide/dependency_verification.html)

真实产品与需求信号：

- [Photomath 官方功能](https://photomath.com/)
- [Photomath 技术与云处理说明](https://support.google.com/photomath/answer/14328660)
- [Khanmigo 使用与安全指南](https://support.khanacademy.org/hc/en-us/articles/13860282793869-What-are-the-Community-Guidelines-for-Khanmigo)
- [试卷宝 App Store 功能](https://apps.apple.com/cn/app/id1497704413)
- [小猿搜题 App Store 功能](https://apps.apple.com/cn/app/id906995758)
- [拍照错题本的真实批量分类/导出反馈](https://apps.apple.com/cn/app/id6448860597)

竞品页面和评论只作为需求/风险信号，不能当作能力准确率或用户总体统计。
