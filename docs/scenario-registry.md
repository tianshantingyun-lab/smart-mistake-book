# 场景注册表与合同治理

状态：治理基线（不等于运行时验收报告）  
日期：2026-07-22  
适用范围：产品决策、实现合同、体系蓝图、信息架构与 `docs/history/design-qa.md`

本注册表只解决跨文档的 ID、权威边界、阶段映射和证据状态。它不因接口、表、测试名称或旧 QA 记录存在，就宣布能力已经通过里程碑。第 4.1 节按日期记录实际重跑结果；其余表格中的自动化条目仍只表示对应覆盖范围，不能替代所属里程碑的完整退出门禁。

产品方向已经从“纯本地优先”校准为“模型优先、端侧可信”。该变化不抹掉历史实现证据，也不允许把已存在的本地 OCR/fixture 误报为最终产品能力。

> **2026-07-22 冻结产品合同：** 底栏固定为 `复习 / 讲题 / 错题本 / 我的`。模型负责语义理解、讲题和当前题内按需 GUI spec；本地负责可信持久化、规则、Schema/allowlist 校验、确定性渲染、调度和密钥。不重点建设离线智能。正常可用候选直接继续；识别失败也不得让学生手工补录题面，只保留重试、重拍或补拍、换图、稍后继续并保留原草稿。只节选继续所必需的一句提示，不向学生展示 OCR、解析、校验、队列等内部流水线状态。没有用户明确要求时，不生成新题、同类题、变式题或校准题，也不得用额外题探测能力；能力边界只来自当前题和历史可信行为。错题本只按 `科目 → 板块 → 知识点` 可见归类，掌握程度独立；错因、来源、题型不作为可见分类。错题本总目录为空显示“还没有错题”，筛选为空使用独立筛选空状态；复习/我的缺少学习证据时才显示“暂无学习记录”。不做手写演算板。记忆跨会话、绑定精确题目修订、记录尝试/提示/答案暴露/卡住步骤/学习变化并可追溯；每次只发送当前题真正相关的最小记忆，模型省略字段或候选不等于删除既有事实，模型也不能直接写掌握度。答案暴露只在绑定精确模型任务请求与响应序号的答案表面底部完整进入真实视口时入账，生成完成、开始渲染、仅顶部可见或恢复后重复显示均不算新的暴露。生产初始化不得自动写入 M1 或其他演示题；真实保存且处于 `ACTIVE` 的错题进入复习计划，复习必须展示已保存的精确题面修订，不得另造题目或答案。当前不部署自建云端，采用 BYOK：受控 Android 客户端直连用户选择的 Provider。首次讲题与续聊按当前 Provider、配置/策略版本和披露范围授权；授权不匹配时重新询问，已缓存对话仍可本地查看。每个按不可变类型输入指纹确定的逻辑外部操作最多允许三次真实 Provider dispatch，本地执行不占该预算；应用重建后仅凭持久化出站清单不得自动重发，必须重新取得当前进程内有效租约或由学生明确点一次“继续”，且恢复精确未完成动作，不能重复首次自动启动。

> **2026-07-23 原子记忆补充：** 题目步骤必须归因到同科原子知识/技能，原子掌握在单科内跨题共享、跨科隔离；可见三级目录不变。知识库没有可靠节点时先形成受控检索请求，不自动写入模型猜测。

> **2026-09-13 逐次发送授权的取代（对齐 2026-09-09 全局同意决定）：** 上一段里「**首次讲题与续聊按当前 Provider、配置/策略版本和披露范围授权；授权不匹配时重新询问**」这句已不再描述现行行为。2026-09-09 的产品裁定把「配置模型 = 全局同意」定死：智能体自主回合（`ModelTaskInput.isAgentConsentEligible`）**发起即外发、不再逐次索要授权**，`ModelEgressPolicy` 直接签发 `ModelExecutionPermit.ProviderConsented`；未列入该集合的请求（TutorLobby、TutorDebrief、ProblemOrganization 等）**仍保持 manifest 门禁与逐次授权不变**。该句所属段落的其余内容（含"三次 dispatch 预算"）同样以增补为准。权威出处：`docs/model-first-product-boundaries.md` 的 2026-09-09 两条 D-001 增补与 `docs/design/tutor-agent-consent-design.md`。实现侧留有的一处残骸（Lobby 的"同意并发送"卡 `TutorLobbyDisclosureCard`）已同期删除。**（2026-09-13 追加：全局同意开关本身也已删除，`ModelAgentConsentStore`、设置页开关与 `tutorAgentChatEnabled(provider, consentEnabled, kind)` 的 `consentEnabled` 形参一并移除，配置好 provider 即唯一条件；请求信封字段 `agentConsentGranted` 保留且调用点恒置 `true`。）**

## 1. 权威边界

| 文档 | 权威范围 | 不得据此声称 |
|---|---|---|
| [`model-first-product-boundaries.md`](model-first-product-boundaries.md) | 当前产品定位、本地/模型职责、Model Gateway 与无模型行为 | 仅因写出契约就声称 Provider、图片理解或讲题已实现 |
| [`student-experience-spec.md`](student-experience-spec.md) | 当前四根页、图片转文档、讲题、错题本、复习、记忆与真实情境覆盖 | 仅因界面存在就声称真实学生任务已通过 |
| [`m1-exhaustive-product-contract.md`](m1-exhaustive-product-contract.md) | 历史 M1–M4 实现合同、验收范围、退出门禁和失败降级；是里程碑证据权威 | 用旧“纯本地优先”覆盖 D-001，或仅因蓝图、界面、接口、测试文件存在就声称某里程碑通过 |
| 本注册表 | ID 命名空间、跨文档映射、证据等级、当前实现边界索引 | 替代 M1 合同的退出门禁，或单独宣布交付完成 |
| [`system-blueprint.md`](system-blueprint.md) | 体系架构、长期状态机、风险目录和技术参考 | 覆盖 M1 合同的验收范围或把历史场景当成当前交付承诺 |
| [`product-information-architecture.md`](product-information-architecture.md) | 导航、页面归属、最短路径和交互假设 | 证明用户习惯已经验证，或证明后端合同已经实现 |
| [`../history/design-qa.md`](../history/design-qa.md) | 带日期的视觉与交互回归记录 | 证明真实数据闭环、数据库故障恢复或 M1 退出门禁 |

冲突处理采用保守规则：做什么产品以及智能职责如何分界，以 D-001/UX-001 与上方 2026-07-22 冻结合同为准；实现是否完成，以 M1 合同与本注册表的可复现证据为准；命名空间和证据等级以本注册表为准；蓝图只提供架构解释。如果两份材料对实现状态不一致，按较低状态处理。特别地，旧信息架构中的“默认设备模式/本地识别”、旧蓝图中的“离线模板讲题/模型只负责措辞”、把 OCR 复核设为学生必经步骤、固定形式的诊断控件和多维可见错题分类均已被当前合同取代，不得继续驱动新实现。

### 1.1 D-001 / UX-001 当前差距基线

以下状态只描述截至 2026-07-22 仓库中可观察到并有对应自动化证据的实现，不是新里程碑通过声明：

| 目标能力 | 当前证据 | 状态 | 下一条关闭证据 |
|---|---|---|---|
| 四根页与方案 1 设计系统 | `SmartMistakeBookRoot` 已有复习/讲题/错题本/我的底栏并共享现有主题组件 | `PARTIAL` | 四根页逐项完成 UX-001 第一任务，并通过真实状态与视觉对照 |
| 图片安全接收与可修订草稿 | `CanonicalSourceAsset`、私有资产、Room 草稿/不可变修订、独立 CAS 修改工作区、请求指纹、原图预览与待处理恢复入口已存在；正常可用候选只提供继续主动作和可选“题面有误，修改”。候选不可用时不开放人工题面补录，只提供重试、重拍或补拍、换图、稍后继续并保留草稿；单页 2–12 道独立题可按模型给出的边界在本地裁切并一次性生成独立草稿；整卷相邻页可在一次明确授权后自动判断并事务合并，歧义或失败保留原草稿 | `PARTIAL` | 完成真实 Provider 复杂题面与跨页判断质量评测，以及进程强杀、陈旧资产清理与低存储矩阵 |
| 模型图片评估与结构化转写 | 已有强类型 `CAPTURE_ASSESS`/`CAPTURE_PARSE`、Room v9 持久任务与有序来源束、逐页质量检查、单题跨页补拍/恢复/联合转写、逐次整束外发授权、受限题图流、OpenAI-compatible 多模态 HTTP 适配器、结构化 AST、块级证据和保真提交；PDF 可在本地渲染为 2–30 个私有页资产并进入同一待处理管线；模型判断单页含 2–12 道独立题时只返回受限区域，本地完成有界裁切和原子拆分；整卷相邻页判断只有 `SAME_QUESTION/NEXT_QUESTION/UNSURE` 三态，模型不能直接合并，Room v23 只在明确同题且状态一致时原子合并，不要求学生框选、重拍、排序或手工合并；受限 Cartesian/SymbolTable 会重建本地 ID 与 `DIAGRAM` 证据，模型不能伪造本地提交状态或预选答案 | `PARTIAL` | 使用用户选定真实 Provider 完成识别与跨页判断质量评测，并补齐复杂学科图回退裁图和身份遮盖 |
| 模型归类、关系与重复候选 | 持久 `PROBLEM_ORGANIZE` v2 主链要求模型在可见科目/板块/知识点之外，把当前题真实步骤归因到同科细粒度知识/技能；本地 Room v24 保存节点类型、粒度、别名、边界和校验状态，并仅将细粒度绑定送入跨题能力投影。无法可靠细化时输出受控知识检索请求、停止自动落库，并将每题触发事实幂等写入静默待研究队列；同科、同父级、同规范化查询共享稳定缺口指纹，便于跨题聚合且不丢失题目来源。经审校知识包把来源、节点、逐节点证据和多个缺口解决合并为一个可精确重放的 Room 事务；任一缺口无效、时间倒置或不可变记录冲突都会整体回滚，避免知识已入库但错题未绑定。缺口只能由同科、细粒度、来源已审校的正式节点关闭。学生端“我的 → 知识点与资料”只读页按科目展示已确认板块、知识点和资料数量，并另行展示待完善分类和涉及题数；不展示内部粒度、检索或存储术语，不提供学生审核、批准或资料权威性判断动作。模型生成的待完善查询和父级名称不再直接展示，非空状态设备测试会阻止工程词意外进入可见文本。Room v24 使用本地派生检索索引先找候选，长题面会均衡覆盖结尾设问、开头条件和分散段落；数据库接口以 512 为硬上限，再只读取候选的直接学习顺序关系。12000 知识点与 11999 条学习顺序关系规模的完整检索多轮实测 20–23 ms，12000 条单人掌握记录读取为 52–95 ms；九科十九个随包细知识点固定回归 19/19 命中且无跨科串入。正式知识库入口同时拒绝模型候选、未审校来源、无可追溯 HTTPS 来源、跨科绑定、缺少同版本父级或缺少逐节点来源证据的导入包。错题本仍只显示科目→板块→知识点，模型省略不表示删除 | `PARTIAL` | 填充课标/教材/获授权教辅来源的全学科细粒度知识目录，落地受控联网检索执行、来源抓取核验与独立于学生主流程的审校工作台，完成独立人工标注题集上的 Recall@K/Precision@K、真实 Provider 归类质量评测、长题面漏检压力集及用户纠正回归 |
| 因材施教聊天与动态 GUI | `TUTOR_PLAN + TUTOR_RESPOND` 已形成持久化聊天链：学生原文与模型 Markdown 按精确会话/题目修订持久化并可跨会话恢复；计划跨教学周期保留有界的学生原文卡点，应用重建后仍可继续当前题。GUI 场景可缺省且每回合最多一个，只允许当前题内 `step_flow`、`comparison`、`evidence_chain`、`process_timeline`、`concept_map`、`formula_derivation`、`spatial_diagram`，由本地白名单校验和确定性渲染；最后一种已覆盖受力、几何、简单化学连接及基础串联电路，模型只给受限元件和连接，本地决定坐标、方向、样式及学生用语。提示词与输出 Schema 禁止额外出题、诊断题和能力探测；模型的答案声明、任务成功或表面顶部进入视口都不是写账依据，只有绑定精确任务请求/响应序号的答案表面底部完整进入真实视口才幂等记录一次暴露事实。新答案完成时不自动把长答案底部滚入视口；学生主动看到答案后，系统先补齐该讲题回合的“答案已展示”状态，再记录一次精确暴露事实，页面重建后可幂等重试 | `PARTIAL` | 完成真实 Provider 教学质量与答案正确性评测、复杂桥式/多支路电路与实验装置、流式体验及真机强杀恢复验收 |
| 可信学习账本与掌握投影 | Attempt、提示/揭晓边界、outbox、掌握投影和复习会话已有 Room 实现；当前 Room v17 延续并迁移 v15 引入的不可变首次作答字段 `choiceId + choiceMarkdown + responseSubmittedAt`，后续纠正不覆盖原回答，旧数据不猜测缺失响应。真实原题复习中的“我已独立做完 / 这里还卡住”只写入绑定精确题面修订的本次复习事实；两种自报均无知识点归因、不是独立能力证据、校准保持 `UNKNOWN`，也不直接改变知识点掌握度。实际可见的讲题答案建立精确题目锚点后只增加题目级暴露次数并安排更近复查 | `PARTIAL` | 补齐卡住步骤和用户纠正的全量投影/重建、真实进程强杀测试；模型与自报捷径写掌握度路径必须保持为 0 |
| 错题本长期资产系统 | 列表、搜索、持久待处理工作台、精确修订详情与不可变版本历史、模型整理与用户纠正、关系跳转、重做、重新讲题、单题导出和当前筛选结果批量 A4 导出已存在；批量导出不要求逐题勾选，自动略过尚未整理完整的个别题，并在列表过大时引导继续使用既有分类筛选。可见目录已固定为 `科目 → 板块 → 知识点`，掌握程度是独立分面。总目录为空显示“还没有错题”和拍照/上传首题入口并隐藏搜索与分面；有题但筛选无结果时使用独立筛选空状态。多张照片或 2–30 页 PDF 可一次导入、逐页落盘、暂停/继续，并对失败页重试或跳过；单页多题会自动成为独立待处理草稿，连续页可自动归入同一道题。结构化题面完全一致的再次采集会在单个 Room 事务内复用原错题、保留新增来源图并记录再次遇到次数，不要求学生判断重复；该次数只在本地提高既有原题的复习优先级 | `PARTIAL` | 完成真实 Provider 跨页质量验收、相似但不完全相同题目的对照裁决、千题目录验收及超百题分卷导出 |
| 复习、打卡与推送 | 可恢复复习会话和本地学习数据已有切片；每日提醒偏好已由 DataStore 持久化，用户主动授权后以非精确本地闹钟调度，重启/改时间或时区会重排，通知不含题面且点击回到复习根页；本地计划器已严格执行时间预算、按题目家族和同源批次去重并混合难度；连续完成天数由已完成复习会话自动计算，不要求学生额外打卡；提醒投递已持久去重，同一时区同一本地日期最多提醒一次，重复广播或日期回拨后再次经过已提醒日期都不会再次打扰 | `PARTIAL` | 继续完成 UX-REV-01–06 的真机整链验收、系统省电/厂商调度偏差和跨多时区长期运行测试；模型只解释既定计划 |
| Provider/API 与密钥 | 当前没有自建云端；设置页、DataStore/Keystore 与 BYOK 端直连已存在。保存配置只写本机且不联网；能力测试仅由用户明确点击，使用彼此独立的合成样例检查结构化输出与图片输入，并绑定精确 Provider、Base URL、模型和凭证 generation。外部执行另绑定提示词策略、披露范围及题图哈希/尺寸 | `PARTIAL` | 使用用户选定的真实 Provider 完成人工合成能力协议，补齐费用说明、像素内身份遮盖与逐任务网络发送预览；真实服务商尚未验证 |
| 无模型任务续跑 | 统一持久 `ModelTask` 状态机、request 指纹、CAS、同一逻辑回合合并和待执行回合恢复已存在；不可变类型输入形成逻辑操作指纹，跨请求的真实 Provider dispatch 原子预留且总数最多三次，本地执行不消费预算。应用重建后恢复精确 `TUTOR_PLAN` / `TUTOR_RESPOND` 动作，已缓存讲解/对话在 Provider 不可用时仍可查看；持久化出站清单本身不能跨进程授权发送，必须有当前内存租约或学生一次明确“继续”。配置或鉴权失败进入设置后返回同一任务并复用原请求；发送许可或授权指纹失效只回到本次发送确认，不误导用户修改 API 配置，也不重复首次自动启动 | `PARTIAL` | 完成真机进程强杀、用户真实 Provider 的联网/配置恢复及远端已接收但本地未落终态的重复调用评测；无 Provider 幂等键时仍按最多三次 dispatch 的至少一次执行管理风险 |

上述体验边界已经进入当前实现；剩余关闭顺序固定为：真实 Provider 识题/讲题质量评测 → 真机强杀与远端幂等风险验证 → 复杂题图、批量与千题规模 → 流式、无障碍和视觉验收。任何自动化或截图只证明其覆盖的切片，不能替代完整退出门禁。

开发顺序不得按“哪个页面看起来容易”决定，而应先关闭共享依赖：用户批准的出站合同与受限资产流 → Model Gateway 与可恢复任务 → 图片转文档 → 讲题/错题本分流 → 学习记忆 → 复习与导出。真实网关不得在此前直接读取 Room、文件系统或密钥存储。

## 2. 场景 ID 命名空间

| 命名空间 | 所有者 | 示例 | 使用规则 |
|---|---|---|---|
| `M1-*` | M1 合同 | `M1-CAP-01`、`M1-TUT-12A`、`M1-HR-05` | 验收场景的 canonical ID；跨文档、测试名、缺陷和验收记录必须使用完整前缀 |
| `BP-*` | 系统蓝图 | `BP-CAP-01`、`BP-TUT-17`、`BP-LIB-04` | 蓝图历史/架构参考场景；不是 M1 合同别名，也不直接形成交付声明 |
| `IA-*` | 产品信息架构 | `IA-NAV-01`、`IA-HABIT-01` | 仅用于导航、页面归属或用户路径决策；不得充当合同验收 ID |

为避免大面积改写历史表格，源文档内保留局部短 ID，但引用时必须规范化：

- M1 合同中的 `CAP-01` 解析为 `M1-CAP-01`；其类别包括 `CAP`、`TRU`、`TUT`、`REV`、`AI`、`SEC` 和本注册表的 `HR`。
- 系统蓝图中的 `CAP-01` 解析为 `BP-CAP-01`；其类别包括 `CAP`、`TUT`、`REV`、`LIB` 和 `ME`。
- 相同短 ID 或相同后缀不表示等价。例如 `M1-CAP-01` 与 `BP-CAP-01` 是两个独立记录；若要表达继承或覆盖，必须显式写出两个完整 ID 和关系。
- 新验收场景只能在 M1 合同命名空间中分配；蓝图新增场景只能分配 `BP-*`；已分配 ID 永不复用。

## 3. Phase 0–4 与 M1–M4 映射

旧 Phase 标签不再作为验收标签。所有新计划、缺陷和发布说明使用 M1–M4 或独立研究阶段 R。

| 历史标签 | canonical 去向 | 明确边界 |
|---|---|---|
| Phase 0 | **pre-M1** | 可运行 UI/架构原型与可信 fixture；不是 M1，也不能声称真实数据闭环 |
| Phase 1 | **M1** | Room 本地学习账本、Attempt/outbox、投影、可解释 ReviewPlan 与同一持久快照驱动的复习闭环 |
| 旧 Phase 2 | **拆分为 M2、M3、M4** | M2 接收采集/按需修订/目录；M3 接收 Provider/当前题内自适应讲解/批处理；M4 接收导出/备份/设备韧性 |
| 旧 Phase 3 | **研究阶段 R** | 手写、公式、复杂图形、全学科知识体系、长期校准等各自独立立项，不等于 M3 |
| 旧 Phase 4 | **拆分为 M3、M4、R** | 受控 Provider 属于 M3；导出/备份/韧性属于 M4；账号、同步和模型平台属于 R |

`design-qa.md` 中的 `P0` / `P1` / `P2` 是视觉缺陷严重度，不是 Phase 编号；引用时必须写成“QA 严重度 P0/P1/P2”。

canonical 路线如下：

| 阶段 | 核心交付边界 |
|---|---|
| pre-M1 | 原型、导航、安全 flavor、可信样例与架构合同 |
| M1 | 本地学习账本与复习闭环；不承诺真实 OCR |
| M2 | 单题采集、CanonicalSourceAsset、按需题面修订、错题目录、检索、模型归类与去重/修订；不得设置强制 OCR 校对关卡 |
| M3 | 受控 Provider、证据约束的自适应讲题、持久生成请求与 PDF/多图批处理 |
| M4 | PDF/Markdown/打印导出、加密备份恢复、迁移、删除语义与设备/发布韧性 |
| R | 长期研究；每项独立数据集、金标和退出门槛，不与 M1–M4 捆绑承诺 |

## 4. 概念唯一写入者到当前实现

状态词：`IMPLEMENTED-SLICE` 只表示当前垂直切片存在；`PARTIAL` 表示只覆盖 fixture、部分命令或部分事务；`NOT-IMPLEMENTED` 表示没有生产写入路径。任何一项都不自动等于 M1 通过。

| 概念数据 | 合同唯一写入者 | 当前具体接口/函数 | 当前事务边界 | 真实状态与缺口 |
|---|---|---|---|---|
| `SourceAsset` / `CanonicalSourceAsset` | `CaptureImportService` / `AssetVault` | `CaptureWorkflowRepository.importDraft()` → `AndroidCanonicalAssetVault.import()`；仅接收本应用私有 FileProvider URI，做字节/尺寸/像素预算、方向校正、EXIF 剥离、PNG 无损保持和 SHA-256 内容寻址；`MlKitChineseQuestionTextRecognizer` 只读取 canonical 文件 | 文件 finalization 与 Room 草稿事务是两个故障域；数据库失败时保留可回收的内容寻址孤儿，绝不猜测删除可能已被并发草稿引用的正式来源；识别前重新校验大小和 SHA-256 | `IMPLEMENTED-SLICE`；单图 canonical writer 与随 APK 打包的中文/拉丁印刷文字 OCR 候选已有，裁切/透视/质检、身份字段处理和低存储恢复仍缺 |
| `ProblemDraft` / `ProblemDraftEditSnapshot` | `DraftService` / `CaptureWorkspaceService` | `StudyDatabasePort.createProblemDraft()` / `splitProblemDraft()` / `reviseProblemDraft()` / `saveProblemDraftEditWorkspace()` / `readProblemDraft()` / `observePendingCaptureDrafts()`；过渡候选或经验证的 `CaptureParseOutput` 可形成候选修订，用户主动修改才写入独立工作区 | Room v7 将语义修订保持 append-only；单页多题在一个事务中创建全部子草稿并废弃原草稿，重放按稳定 ID 和请求指纹校验；Room v23 的连续页事务追加被确认的来源束、废弃后页草稿并重映射同批页面，任一步失败整体回滚；修改工作区按 `basisRevision + workspaceVersion` CAS 覆盖且不推动 draft head。待处理恢复在同一读事务中批量读取草稿头、最新评估/转写、来源束、修改工作区和临时讲题状态，按稳定顺序发布 | `IMPLEMENTED-SLICE`；多张照片或 PDF 各页先形成独立草稿，单页多题可自动拆分，明确连续页可自动合并，批量恢复避免逐草稿 N+1。真实 Provider 跨页质量与进程强杀矩阵仍缺 |
| `ModelTask` / `ModelTaskEvent` / `ModelTaskLogicalOperation` | `ModelTaskRepository` | `RoomModelTaskRepository.execute()` / `observe()` → `ModelEgressPolicy.authorize()` → `ModelGateway.execute()`；真实图片适配器只能经 `RestrictedModelAssetSource.open()` 获取授权字节流；任务保存完整输出，事件只留状态审计 | 当前行按 stateVersion/status CAS；request SHA-256 与不可变类型输入形成的逻辑操作指纹在领域和数据库边界重新验证。真实 Provider 执行在 transport 前原子预留 dispatch，跨请求合计最多三次；本地执行走普通状态迁移，不预留也不消耗外部预算。外部执行还要求当前提示策略及短时精确授权，缺失或失配时在适配器 I/O 前失败且不发网络请求 | `IMPLEMENTED-SLICE`；评估/解析、短期精确授权、受限资产流、跨请求三次 dispatch 上限和演示/不可用 Gateway 已有；真实 Provider 兼容性、远端幂等和题目删除级联仍缺 |
| `Problem` / `ProblemRevision` / `PracticeUnit` / `ErrorBookEntry` | `ProblemCatalog` / `CommitProblemDraft` / `ErrorBookService` | `CaptureWorkflowRepository.confirmAndCommit()` → `StudyDatabasePort.commitProblemDraft()`；curated fixture 仅由测试/显式演示入口加载，生产初始化路径不可达 | `ProblemDraftTransactionDao.commit()` 在单一 Room `@Transaction` 中验证候选的内部可提交状态，再创建四类正式记录、来源关联、回执并标记草稿；正常可用候选不要求学生逐块操作。未改动块由本地策略标记 `LOCAL_POLICY_ACCEPTED` 并保留原 provenance/confidence，只有学生真正修改的块才是 `USER_CONFIRMED`；command/payload 指纹支持精确重放 | `IMPLEMENTED-SLICE`；生产单题提交和后续归类已存在，但重复/修订/变式选择、删除/恢复仍不完整 |
| `ClassificationBinding` / `ProblemRelation` | `ClassificationService` / `RelationService` | `RoomMistakeOrganizationRepository` 建立持久模型任务；成功后本地阈值策略合并完整的科目+板块+知识点候选，显式纠正再提交 `ProblemOrganizationSelection`；另有 `StudyDatabasePort.markRelationsStaleForRevision()` | `RoomProblemOrganizationStore.confirm()` 在单一 Room 写事务中重新验证题目修订、维度、证据和时间单调性；自动整理与用户保存默认合并，模型省略不删除。对三层目录的 `USER_CORRECTED` 绑定及仍被证据引用的关系保留；关系逐条提供移除/恢复，保存只应用明确差异，只有显式清空才清除全部 | `IMPLEMENTED-SLICE`；正常路径无逐项勾选，低置信或缺层级不覆盖旧分类；可见目录固定为科目、板块、知识点，掌握程度独立；重复并排比较/合并与真实 Provider 质量仍缺 |
| `AssessmentItem` / 可信证据快照 | 教学材料目录与受控提交用例 | `StudyExperienceRepository.teachingArtifact()`；提交前调用 `StudyDatabasePort.saveAssessmentEvidenceSnapshot()` | `AttemptTransactionDao.saveAssessmentEvidenceSnapshot()` 的 Room `@Transaction` | `IMPLEMENTED-SLICE`；只覆盖 curated M1 选择题路径，不是通用目录；真实已保存错题进入原题复习不依赖 teaching artifact |
| `Attempt` / `AttemptCorrection` / ledger outbox | `AttemptService` | `StudyExperienceRepository.submitChoice()` → `StudyDatabasePort.recordAttempt()`；底层另有 `appendAttemptCorrection()` | `AttemptTransactionDao.recordAttempt()` / `appendCorrection()` 的 Room `@Transaction`；当前 Room v17 沿用并迁移 v15 引入的不可变首次响应，保存 `choiceId + choiceMarkdown + responseSubmittedAt` 并纳入精确重放校验 | `IMPLEMENTED-SLICE`；纠正只追加且不覆盖首次回答；旧行明确读取为 `LegacyUnavailable`，不得猜测。Correction 尚无完整产品入口 |
| `ANSWER_REVEALED` / `AnswerRevealOutcome` / `TutorAnswerExposureOutcome` | `AttemptService` / `TutorSessionService` | 受控作答揭示继续走 `recordAnswerReveal()`；手动展开或模型回复含答案时先只更新显示状态，绑定精确 `modelTaskRequestId + surfaceKind + responseOrdinal` 的答案表面底部完整进入真实视口后才走 `recordSolutionExposure()`；未入库临时会话先保存会话事实，建立题目锚点后幂等衔接学习账本 | Attempt 揭示与讲题曝光分别由 Room 事务写入；讲题曝光按精确模型任务/表面/响应槽唯一去重，不用同回合其他回复替代证据。它只更新 `ProblemMemory` 的暴露次数与复查节奏，绝不直接改变 `KnowledgeMastery`；ledger head 变化会刷新当前学习快照 | `IMPLEMENTED-SLICE`；未滚到答案表面底部不入账、可见幂等、同回合多回复隔离、锚点竞态与当前快照刷新已有专项覆盖；模型声明、生成成功和旧讲题行都不反推可见性。物理设备强杀组合仍待验收 |
| `ProblemMemory` / `KnowledgeMastery` / projection checkpoint | `LearningProjectionEngine` | `LearningProjector.project()` / `replay()` → `StudyDatabasePort.commitProjection()` | `ProjectionTransactionDao.commitProjection()` 的 Room `@Transaction` + checkpoint/stateVersion CAS | `IMPLEMENTED-SLICE`；有规则与持久化覆盖，完整崩溃注入门禁未完成 |
| `AbilityBoundaryProjection` | `LearningProjectionEngine` | 无单独摸底或模型直写接口；现有知识掌握只从用户给出的题与可信行为投影 | 无 | `PARTIAL`；后续只能补足可追溯投影，不能为收集数据另出题，也不新增错因/来源/题型可见分类 |
| `ReviewPlan` / Queue | `ReviewPlanner` | `ReviewPlanner.plan()`，由 `RoomBackedStudyExperienceRepository` 调用 `StudyDatabasePort.saveReviewPlan()`；生产初始化不自动种入 M1，所有真实保存且处于 `ACTIVE` 的错题按当前精确修订进入计划；curated fixture 只由测试或演示显式请求 | `ReviewPlanTransactionDao.savePlan()` 的 Room `@Transaction` | `IMPLEMENTED-SLICE`；真实 ACTIVE 错题排程已有直接覆盖，真实规模/时区矩阵未验收 |
| `ReviewSession` | 复习会话用例 | `startOrResumeReviewSession()`；curated 选择题走 `submitReviewChoice()`，真实保存题走 `submitReviewSelfReport()`。真实题页面只显示数据库保存的精确原题修订，不生成另一道题或虚构答案；“这里还卡住”推进当前复习后进入同一题同一修订的讲题 | 两条提交路径都必须在单一 Room 事务中写入精确 Attempt/receipt 并 CAS 推进；UI 持久化首次完整命令，失败或重建后精确重放，未知结果时阻止重复改报 | `IMPLEMENTED-SLICE`；选择提交与真实题自报的成功、回滚、幂等/重建已有专项覆盖；仍缺真实规模和物理设备强杀矩阵 |
| `TutorSession` / `TutorTurnResponse` / `TUTOR_RESPOND` / 通用 `AssessmentEvent` | `TutorSessionService` / `TutorInteractionRepository` / `ModelTaskRepository` | 拍题讲解建立绑定精确 draft revision 的持久临时会话；`TUTOR_PLAN` 约束当前题教学边界，并保留有界的前序教学周期学生原文；`TUTOR_RESPOND` 保存当前学生原文、可见讲解、连续有界历史和模型 Markdown。计划、选择与自由回复按稳定时间线合并到同一聊天流；可选视觉场景只允许 `step_flow`、`comparison`、`evidence_chain`、`process_timeline`、`concept_map`、`formula_derivation`、`spatial_diagram`。最后一种只接收九个固定位置、有限节点/连线及基础电路元件，本地重建 ID、坐标、方向、样式及箭头 | 会话建立、结束及可选正式提交使用 Room 事务；模型生成任务与学生事实分属两个权威边界。一个逻辑回复槽与真实选择时间分别持久化；重建后仍能从学生真实卡点继续，但这些原文只服务当前题，不授权补问能力、校准或额外出题。跨题学习概览只有命中当前题显式关联的 knowledge node ID 才能进入模型输入；未建立关联时发送空集合，本题精确记忆仍单独保留。答案曝光以精确模型任务请求/表面/响应槽事实衔接题目记忆；重建后的外部动作必须有当前租约或一次明确继续。视觉文本还要通过学生语言边界，穿点、交叉、越界、断开的元件连接、电路/普通图混用、任意坐标/样式/图片/动作均失败关闭 | `IMPLEMENTED-SLICE`；自然聊天、精确修订隔离、应用重建恢复、固定输入框、可选本地 GUI、动作/揭示事实、跨周期卡点和续聊授权已接通；图形讲解已覆盖受力、三角形、简单化学连接和基础串联电路的 API 36 受控场景。真实 Provider 教学质量与答案正确性、复杂桥式/多支路电路与实验装置、流式渲染及真机强杀仍缺 |
| 四根页学习快照 | `StudyExperienceRepository` 组合读模型 | `RoomBackedStudyExperienceRepository.snapshot: StateFlow<StudyExperienceSnapshot>` | 读取已提交 Room 状态；发布前要求 current projection；ERROR 时清空可交互决策 | `IMPLEMENTED-SLICE`；Android 16 当前应用级回归为 `localFirst 20/20`、`strictOffline 14 executed + 6 boundary-skipped`，strict 合并 Manifest 无网络权限；M1 物理设备强杀矩阵仍未通过 |
| API/NetworkPolicy/凭证 | `SettingsService` / `CredentialVault` | `ModelConfigurationStore.save()` / `rotateApiKey()` / `clear()` → `DataStoreModelConfigurationStore` / Keystore vault；当前仅 BYOK，OpenAI-compatible `/chat/completions` 适配器由受控 Android 客户端直连用户所选 Provider。保存配置不联网；用户主动点击能力测试后，两个独立合成探针分别检查结构化输出与图片输入 | DataStore 与 Keystore 使用不含密钥的 durable pending journal；测试结果绑定精确 Provider、Base URL、模型与凭证 generation。HTTP 端点和 DNS 完成准备后、OkHttp 真正 `enqueue` 前重新读取凭据并复核完整 External 授权；清除/轮换 Key、配置改变、授权过期或 LocalOnly permit 均在入队前失败关闭。配置/鉴权错误返回设置并保留原请求；发送许可或授权指纹失效只回到本次发送确认。API Key 仅用于网络认证，不进入模型输入 | `IMPLEMENTED-SLICE`；本地配置补偿、最小披露、能力探针、错误分流和 HTTP 边界已形成切片；当前无自建云端，用户真实 Provider 人工协议、逐请求费用提示和 Provider 幂等仍缺 |

### 4.1 当前自动验证记录（2026-07-21—2026-07-22）

- 图片转文档待处理工作台历史切片曾通过：拍题中断、模型任务、可选题面修改、临时讲题和来源缺失沿用持久草稿身份恢复，不重复建题或会话；当时工作台曾按内部阶段汇总。恢复身份与幂等证据仍可复用。当前正常可用候选已收敛为一个继续主动作和可选“题面有误，修改”；候选不可用时不开放人工题面补录，只提供重试、重拍或补拍、换图、稍后继续，并隐藏内部任务名。
- 真实错题复习切片于 2026-07-22 新增并通过专项验证：全新生产数据库重复初始化均不自动写入 M1 或其他演示题；所有真实保存且处于 `ACTIVE` 的错题进入计划。复习页展示保存的精确原题修订，不依赖 curated teaching artifact，也不生成另一道题或虚构答案。“我已独立做完 / 这里还卡住”以精确命令原子写入并推进；两种自报均无知识点归因、不是独立能力证据且不改变掌握度，卡住后只进入同一题同一修订的讲题。
- 错题关系与题面版本切片新增并通过：详情页展示不可变题面版本历史，明确当前版/历史只读；历史版允许核对和单版 A4 导出，但不允许讲题或重新整理，损坏旧版仍提供回到当前版的恢复动作。关系卡只显示关系语义、目标题名与是否可打开，不向学生展示模型置信度；只有精确目标题面修订仍存在时才可打开，目标换版会提示重新整理而不静默跳转。专项 API 36 模拟器测试中 Room 精确版本历史 `1/1`、错题整理及关系/版本界面 `6/6` 均通过；ADB 真实触控完成“复习 → 错题本 → 题目详情”，当前界面与既有详情页基线同屏视觉核对，截图为 `design/qa/mistake-detail-revision-current.png`。随后全工程 `312/312` 个 JVM 测试、双 flavor Lint、双 APK 构建同轮 `484` 个 Gradle 任务成功；Lint 为 0 error，strict/localFirst 各有 `10/11` 条既有非阻断兼容性、图标和依赖提示。该切片当时仍缺待处理工作台；该缺口已由上方后续切片关闭。重复题并排比较/合并、批量导入导出和千题性能仍未关闭。
- 每日复习提醒切片新增并通过：原“本地提醒草稿”假开关已替换为可持久化的开关、18:30/20:30/22:00 快捷时间与自定义时间；Android 13+ 只在用户主动开启时请求通知权限；非精确 `RTC_WAKEUP` 闹钟在启动、重启、系统时间和时区变化后重排，不要求精确闹钟权限。通知正文不含具体题目且不触发模型调用，点击会回到“复习”根页。专项 JVM 时间/DST与协调器测试通过；API 36 模拟器上设置交互/深链 `1/1`、真实通知 `1/1`、DataStore 重建 `1/1`，ADB `dumpsys alarm` 直接确认 `DAILY_REVIEW_REMINDER` 为 20:30 的非精确窗口，当前截图为 `design/qa/reminder-current.png`。随后全工程 `311/311` 个 JVM 测试、双 flavor Lint、双 APK 构建同轮 `484` 个 Gradle 任务成功；Lint 为 0 error，strict/localFirst 各有 `10/11` 条现有非阻断兼容性、图标和依赖提示。自动打卡仍必须以后续有效 Attempt 合同实现，不能把通知到达或点击当打卡。
- 错题重进讲题切片新增并通过：错题详情以当前不可变 `MistakeRevisionKey` 打开统一讲题组件；同题同修订生成稳定会话身份并恢复持久 `TUTOR_PLAN` 历史，新修订自动隔离旧上下文。专项策略单测通过；API 36 模拟器上错题本 `6/6`、讲题 `4/4`，ADB 真实触控完成“错题本 → 题目卡片 → 讲解这道题 → 已存题讲题页”，crash buffer 为空，截图为 `design/qa/saved-mistake-tutor-current.png`。随后全工程 JVM 测试、双 flavor Lint 与双 APK 构建同轮 `478` 个 Gradle 任务成功；两份 Lint 均为 0 error，仍保留非阻断兼容性、图标和依赖提示。
- 严格三遍复审后的恢复/性能收口新增并通过：多知识点分面索引由反复复制列表改为一次线性建表；图片评估 schema 升至 v2，同时保留 v1 待处理任务可读；分类确认增加同题同修订的单调时间门，迟到旧命令不能回滚新纠正。专项 JVM/编译回归通过，API 36 模拟器上 Room `52/52`、错题本 `5/5`、录入 `16/16`；ADB 按 UI tree 坐标完成复习页→错题本真实点击，关键搜索/分面节点存在且 crash buffer 为空，截图为 `design/qa/review-library-current.png`。
- 图片转文档多题边界升级：模型提示明确区分“同题跨页”和“多道独立题”；`MULTIPLE_QUESTIONS` 必须是 `BLOCKING + SPLIT` 并返回 2–12 个有界、低重叠区域，否则输出合同失败关闭。本地逐区裁切，并在一个 Room 事务中创建全部独立草稿、废弃原草稿；失败时保留原图和原草稿，界面不要求学生框选、校对或重拍。
- 错题本目录已收敛：学生可见分面固定为科目、板块、知识点和独立掌握程度；多知识点可逐项筛选。总目录为空显示“还没有错题”，有题但当前筛选为空显示独立筛选空状态；“暂无学习记录”只用于没有学习证据的复习/我的页面。`PROBLEM_ORGANIZE` 只提供模型语义候选，本地阈值策略自动接受完整的高可信层级；自动重整时模型遗漏不是删除信号，既有已接受标签会在维度预算内保留，只有用户明确纠正才可替换。正常路径没有逐项勾选，学生只有发现错误时才打开纠正。关系只处理本机已有题，不能生成题目或探测能力。
- 讲题聊天与动态 GUI 切片于 2026-07-22 新增并通过专项验证，并于 2026-07-23 增加“式子怎么变”的逐步推导场景：`TUTOR_RESPOND` 将学生原文和模型 Markdown 绑定精确会话、题目修订与回合序号，恢复时按完整逻辑交换去重并保留最近连续完整对话；当前题内视觉场景可省略且最多一个，只允许 `step_flow`、`comparison`、`evidence_chain`、`process_timeline`、`concept_map`、`formula_derivation`，本地重建 ID 并拒绝图片、SVG、HTML/CSS/JS、代码、URL、任意动作和布局。`TUTOR_RESPOND` 提示词与输出合同不含诊断题字段，不生成额外题或能力探测；“我不确定，给我一点提示”发送精确学生原文但不写选择作答、能力或掌握证据。
- 知识本体导入闸门于 2026-07-23 加固：`RoomStudyDatabase.importKnowledgeBase` 在事务前统一校验来源类型、许可状态、HTTPS 可追溯地址、SHA-256 指纹、人工审校时间、逐节点来源绑定、同科/同版本父级和无环主题层级；`MODEL_CANDIDATE` 永远不能直接进入正式知识本体。受控联网检索执行器与人工审校工作台仍未完成，不能把“可安全导入”误报为“已经会联网补库”。
- 知识缺口静默队列于 2026-07-23 接入 Room v19：成功的 `PROBLEM_ORGANIZE` 若返回 `groundingRequests`，不会保存模型候选或打断学生，而会按精确模型请求指纹、题目修订和请求序号幂等记录每次触发；另以同科、同父级、同规范化查询的 SHA-256 指纹聚合同类缺口。当前仅完成可信排队，联网检索执行、来源抓取核验、候选审校和状态闭环仍缺。
- 知识缺口解决于 2026-07-23 接入 Room v20：解决目标必须是同科、原子粒度、`SOURCE_GROUNDED` 且在解决时间前已有人工审校来源证据的正式节点；同一事务为所有相关练习单元建立规范化绑定、写入不可变审计记录并关闭全部待处理事实，精确重放保持幂等，改绑冲突或任一步失败均回滚。由此关闭的是可信落库边界；受控联网检索执行、来源抓取和独立审校工作台仍未实现。
- 经审校知识包应用边界于 2026-07-23 接通：来源、知识节点、逐节点证据和多个缺口解决现在可以作为一个可精确重放的 Room 事务提交；节点输入会按父级先行排序，已有记录必须逐字段一致，任一后续缺口不存在也会回滚先前已经执行的解决与全部本体导入。该边界为未来受控检索结果提供安全落点，但当前仍没有自动联网检索、网页抓取或学生可见审校界面。
- “我的 → 知识点与资料”于 2026-07-23 接入真实 Room 聚合流：入口无红点、无弹窗；二级页从知识节点、逐节点来源绑定和静默缺口队列分别聚合“已确认分类”与“待完善分类”。当前随包基线实际显示九科、9 个板块、19 个细知识点和 9 份已核验资料；这些是可追溯节点数量，不是课程覆盖率或全量完成声明。页面按科目显示数量，不展示来源正文，没有审核或批准动作，学生不承担系统资料审校；当前没有缺口时使用安静空状态，也不把“待补齐”误报为已经会联网补库。
- 单科知识召回于 2026-07-23 改为“本地大候选、外部小披露”：Room 最多读取 8192 个同科且已核验的知识点，本地按题面、规范名、别名、上级分类和能力边界排序，并连同已审校的先修知识扩展后，最多向归类模型披露 64 个节点。8002 个候选连续执行 10 次的 JVM 实测为 600ms；SQLite 同结构读取 8002 行约 15ms且使用现有 `(subject, canonical_name)` 索引。该数据是本机开发环境基线，不替代后续真机多档性能门禁。
- Room v21 新增带资料依据和审校时间的知识点先修关系：只允许同科、已核验的细粒度知识点建立 `PREREQUISITE_OF`，拒绝自环、跨科、缺失资料、时间倒置和有向环；随包基线先落地 2 条关系。经审校知识包可在同一事务中写入来源、节点、证据、关系和缺口解决，失败整体回滚；模型只能从已披露的先修关系中选择，不能自行创造关系。学生端不出现“原子知识、知识本体、grounding、projection”等工程词，入口改为“知识点与资料”。
- 2026-07-23 随包知识内容已从 462 行 Kotlin 硬编码迁移为版本化严格 JSON 资源；解析会拒绝字段漂移、重复科目、未知类型、跨科或缺失的学习顺序目标，并继续经过数据库来源与无环校验。Room v24 为按科目定向读取直接学习顺序增加组合索引，API 36 执行计划确认候选检索和关系读取均命中预期索引。单科 12000 个知识点、11999 条关系的最新十次完整检索为 `[21, 21, 23, 20, 21, 21, 22, 20, 22, 22] ms`、p95 `23 ms`；12000 条学习情况读取为 `[95, 67, 59, 55, 56, 54, 59, 55, 56, 52] ms`、p95 `95 ms`。九科十九条固定表达回归为 19/19 且目标知识点在同粒度候选首位、跨科串入为 0。这关闭的是可扩展内容通路和当前模拟器性能门槛，不代表九科目录已经填满，也不替代人工标注 Recall@K/Precision@K 与 ARM 真机验证。
- 受控资料补充边界于 2026-07-23 落地：联网查询只含缺口指纹、科目、短查询、期望板块和跨题聚合数量，不含题面、题图、答案、对话、学生标识或学习记录；候选资料按来源等级稳定排序、去重，单次最多保留 12 个候选、核验 4 份且并发不超过 2。搜索服务不能自证“官方”或“已授权”：本地只保留中国政府域名上的课程标准声明，教材/教培许可必须命中本地许可主机清单，其余一律降为普通参考资料。远程核验只允许公网 HTTPS 443，固定 DNS，禁用代理、重定向和自动重试，只接受完整 HTTP 200 与 PDF/HTML/XHTML/纯文本，解压后上限 16 MiB；非 200 在读取正文前拒绝，按需增长的有界缓冲区在成功或失败后清零。核验结果只能进入独立审校队列，不能自动导入正式目录或改写学习情况。Room v25 已持久化资料包和逐份核验元数据，相同内容可精确重放、相同标识的不同内容失败关闭；批准/拒绝决定原子记录审校者、说明与时间，可精确重放且不能被另一决定覆盖，已经审校的同一研究任务再次入队也不会被重新打开。API 36 模拟器持久化、不可改写决定、24→25 迁移与最大分页读取 `4/4` 通过。最大单页 256 个资料包、每包 4 份资料采用两次批量查询，最新十次读取为 `[11, 14, 16, 16, 17, 17, 18, 18, 18, 19] ms`、p95 `18 ms`。生产搜索适配器、内部审校工作台、正式内容包组装入口及完整九科内容仍未实现，因此整体状态保持 `PARTIAL`。
- 同一切片已覆盖首次拍题、续聊与恢复授权：讲题空状态只保留“拍题讲解”和“从错题本选择”；拍题页才提供“拍照并整理 / 选图并整理”，并用一行说明限定为当前模型、当前题图且不附带其他题目或学习记录。该点击意图仅在当前进程内短时有效，精确绑定 Provider、配置、草稿和全部返回资产；系统选择器返回后不重复确认，重建或失配则只显示一次“继续整理这道题”。讲题只披露当前题、少量相关学习记录、本题消息和已显示讲解；续聊按当前 Provider、配置/提示词策略与发送范围授权，旧 Provider/旧策略不能静默沿用。已有缓存内容在 Provider 不可用时仍可查看，未完成回合在应用重建后恢复精确 `plan / new response / retry response(requestId)`，不重复创建本地逻辑回合或偷换动作。
- 错题本入口文案收口：主按钮“拍照或上传错题”覆盖相机、相册和截图；“整卷导入”支持多照片或 2–30 页 PDF，一次选择后逐页私有落盘，并支持暂停/继续、失败页重试或跳过和待处理稳定恢复。单页多题已经自动拆分；整卷页面全部落盘后只需一次“自动分好题目”确认，明确连续页会原子合并，不确定时保留独立草稿且不把合并、排序交给学生。用户真实 Provider 的跨页判断质量仍需专项验收。
- 连续页判断于 2026-07-23 改为窗口批处理：一次模型请求最多比较 8 张连续页面、返回 7 个相邻边界，30 页整卷最多拆成 5 个请求窗口；模型只判断“接上页 / 下一题 / 不确定”，不确定、图片需重拍或本地状态变化时均保留原草稿。API 36 模拟器上 4 页、3 边界用例只执行 1 次模型请求，用例耗时约 0.36 秒；`core:data` 设备测试 `9/9`、错题本界面设备测试 `3/3` 通过。该数字是确定性测试网关和模拟器基线，不代表真实 Provider 网络时延或跨页判断准确率。
- 结构化图形闭环新增并通过：真实 Gateway 测试证明模型 ID/书写层不会越过本地信任边界；PDF 单元测试通过；API 36 模拟器上 PDF 渲染/完整性 `11/11`、错题导出页预览/保存/分享/打印 `3/3`。当前支持受限 Cartesian 与 SymbolTable，复杂学科图仍按合同回退而不猜测。
- 当前筛选结果批量导出于 2026-07-23 接通：错题本直接显示“导出当前 N 道”，搜索、科目、板块、知识点和掌握程度共同决定范围，不增加逐题勾选步骤；正式题面不可用的个别题自动略过并只给一句结果说明。批量读取由逐题查询收敛为一个 Room 事务内的两次集合查询，API 36 模拟器上 100 条当前题面快照连续五次为 `2/3/4/5/6 ms`、中位 `4 ms`；60 道长题生成 64 页 PDF 用时 `435 ms`，并通过批量 120 页、24 MiB 和完整性边界。超 100 道时不把全部 ID 写入可恢复界面状态，而是要求沿用既有分类缩小范围；后续仍需自动分卷以关闭超百题整库导出。
- 模型配置响应式状态修正后，真实手指路径完成“我的 → 智能能力 → 保存 → 返回已保存状态 → 清除 → 返回未配置状态”；API Key 在控件树中无明文。应用级设备回归按唯一模拟器序列分别重跑，`localFirstDebug 10/10` 与 `strictOfflineDebug 10/10` 均通过。全工程 JVM 测试、双 flavor Lint 与双 APK 构建同轮 `478` 个 Gradle 任务成功；OpenAI-compatible 网关专项测试再次通过，并把 HTTP/SSRF/超时/响应上限独立为传输边界。
- 错题智能整理界面状态于 2026-07-23 收敛为单一、可穷举的本地状态模型：模型配置读取、准备、首次确认、暂停恢复、运行、生成失败、结果不可用、本地保存、保留用户修改和完成不再由页面中的交叠条件分别判断。异常结果会直接提供“重新整理”，恢复数据暂时不完整时退回同一个重试入口，不让学生处理内部状态。状态迁移单测覆盖全部运行状态、失败回退、恢复确认、旧请求结果隔离和本地保存结果；API 36 模拟器上的错题整理界面回归 `11/11` 通过。
- 本轮出站合同切片新增并通过：`core:model` 授权/旧指纹单元测试、录入授权策略单元测试与 `core:data` 网关测试证明缺授权时真实 Gateway 调用计数为 0，精确授权后的受限流重新校验 SHA-256/尺寸/字节数。Gateway 在打开任何图片前以 `Long` 计算 Base64 与 JSON 外壳上界，完整请求必须严格小于统一的 36 MiB transport 上限；总量超限、算术溢出或读取时大小变化均在 HTTP 前失败关闭且不泄露资产细节。
- 错题整理恢复边界新增并通过：外部 `PROBLEM_CLASSIFY` 处于 pending 或 retryable 时，进程重建后只显示“整理已暂停 / 继续整理”，不得仅凭持久 manifest 自动发送；一次明确继续会创建新 request/authorization，但保留完全相同的原始分类输入和发生时间。纯本地无外发任务可幂等自动恢复，已成功任务只从持久结果 hydrate/apply。
- 本轮强制重跑 JVM 测试 `248/248`：模型/领域、Room 端口、数据仓库、PDF、录入、错题本与讲题均为 0 失败、0 错误、0 跳过；结构化解析、请求/内容指纹、工作区 CAS、Provider 文本边界、精确导出与保存恢复均有直接测试。
- Pixel 7 AVD、Android 16 / API 36 按模块串行重跑设备测试：Room `45/45`、`core:data 31/31`、PDF `10/10`、录入 `11/11`、错题本 `3/3`、讲题 `2/2`、应用根体验 `localFirstDebug 10/10`，合计 `102/102`；失败/错误/跳过均为 0。
- `JVM test + lintStrictOfflineDebug + lintLocalFirstDebug + assembleStrictOfflineDebug + assembleLocalFirstDebug` 同轮 `460` 个 Gradle 任务成功；两个 APK 均重新生成。Lint 报告只有兼容性、依赖版本与图标建议，没有 Error/Fatal。
- 最后一次学生化文案修正后，录入/错题本单元测试、`lintLocalFirstDebug`、`assembleLocalFirstDebug` 共 `395` 个任务成功；受影响的录入、错题本和应用根体验设备测试再跑 `24/24`，确认新 APK 与断言一致。
- “我的 → 智能能力”文案进一步学生化后，`lintLocalFirstDebug + assembleLocalFirstDebug` 再跑 `385` 个任务成功，应用根体验（含 API 配置安全保存）再次 `10/10`。
- 独立审查后的全局学生化措辞收口，再跑录入/错题本/我的单元测试、`lintLocalFirstDebug` 与 `assembleLocalFirstDebug` 共 `397` 个任务成功；设备端录入、错题本与应用根体验重跑 `24/24`。首次应用重跑发现一条旧文案断言，更新断言后完整 `10/10` 通过。
- 2026-07-22 当前整体验证按唯一 API 36 模拟器串行执行，避免多个 instrumentation task 抢占设备：app `localFirst 20/20`、app `strictOffline 20` 项中 `14` 执行通过且 `6` 项按边界跳过、Room `88/88`、`core:data 54/54`、PDF/导出 `11/11`、录入 `23/23`、错题本 `21/21`、讲题 `46/46`。JUnit XML 汇总 `283` 项、`277` 执行通过、`6` 跳过、`0` 失败、`0` 错误；跳过项仅属于 strictOffline 能力边界，联网主版本无跳过。本轮实际刷新生成的 JVM XML 为 `495/495`；`lint + assembleLocalFirstDebug + assembleStrictOfflineDebug` 共 `449` 个 Gradle 任务成功，11 份 Lint XML 合计 `0 error`、46 条非阻断警告，两个 APK 均重新生成。
- 同轮讲题验证覆盖非评分式“不确定”入口、精确学生原文、短时 Provider/策略授权、超长回复底部真实可见后才记录答案暴露、账本头变化即时刷新当前题记忆、显式关联知识点的最小披露，以及 HTTP 前完整重新授权。方案 1 与当前截图在同一视觉输入中复核后，只发现提示入口继承测试主题紫色；改用 Jade 令牌后重新截图并复核，未再发现可见配色偏差。最终交互态与完整底栏截图分别为 `design/qa/final-live-current/20260722-post-hint/tutor-active-current-green.png` 和 `design/qa/final-live-current/20260722-post-hint/tutor-saved-shell-current.png`。
- 当前合并 Manifest 已重新读取：strict 不含 `INTERNET`/`ACCESS_NETWORK_STATE`；localFirst 仅按能力合同声明这两项网络权限；拍题与 PDF FileProvider 均 `exported=false` 且 authority 按 flavor 隔离。
- 2026-07-21 的历史真机路径从系统 Photo Picker 选图，经私有 canonical 资产、当时的结构化候选修改页与正式提交生成第 5 道错题；随后从精确修订详情进入同一份 A4 PDF 的预览、保存、分享和打印。完整链截图位于 `design/qa/final-live-20260721/`；当时的录入界面截图位于 `design/qa/final-live-20260721-postcopy/`，不得用来代表 2026-07-22 已收敛的正常态交互。
- Android SDK、ADB、API 36 模拟器、JDK 与 Gradle 全部从 D 盘工作区脚本调用。工具超时产生的 Windows profile 身份文件均可恢复地隔离到 `D:\智能错题本\.artifacts\android-profile-recovery\`，工作区主身份仍位于 `D:\智能错题本\.android`；当前 C 盘 Android profile 路径为空。
- 这些证据升级的是当前 curated 垂直切片，不替代下方 M1 整体门禁。

## 5. M1 高风险合同验证追踪

以下 ID 对应 M1 合同“高风险组合测试清单”的行顺序。状态含义：

- `COVERAGE-PRESENT-NOT-RERUN`：仓库有直接自动化覆盖，但本次文档审计未执行。
- `PARTIAL`：只覆盖组合中的部分维度，不能据此通过该合同。
- `MANUAL-PENDING`：需要可重复人工协议，当前没有合格记录。
- `NOT-IMPLEMENTED`：生产能力尚不存在；测试占位或视觉稿不改变此状态。

| canonical ID | 高风险合同 | 目标阶段 | 自动验证追踪 | 人工验证追踪 | 当前状态（2026-07-20） |
|---|---|---|---|---|---|
| `M1-HR-01` | 模糊多题页 × 低存储 × 进程死亡 | M2/M4 | 单题链已有输入字节/尺寸/像素预算、canonical 持久化和幂等草稿；单页多题已有模型边界、本地有界裁切、原子写入和幂等重放；仍没有低存储注入或进程死亡矩阵 | 无记录 | `PARTIAL` |
| `M1-HR-02` | 跨页题 × 第二页失败 × 用户先编辑第一页 | M3 | Room v9 已实现单题有序来源束、逐页质检、幂等追加和整束解析；后页失败保留前页，最新逐页任务失败关闭，旧 PASS 不会回退生效；校对工作区仍绑定题面 revision | 尚缺“先编辑第一页后再补拍第二页”的完整 UI 强杀矩阵 | `PARTIAL` |
| `M1-HR-03` | 手写覆盖 × 公式低置信 × 无可信答案 | M2/R；能力门 M1 | 结构化候选保留印刷/手写/混合层和来源区域供用户按需修订；`TutorCapabilityGateTest.missing verified teaching artifact blocks tutor` 覆盖能力门；没有真实 HWR/公式置信输出 | 需真实混合题面协议；无记录 | `PARTIAL` |
| `M1-HR-04` | 精确重复 × 连续保存 × 网络重试 | M2 | 生产采集已覆盖同像素并发导入复用一个 canonical asset、不同草稿独立存在、同一 `CommitProblemDraft` 精确重放不重复建题，以及不同照片形成相同结构化题面时自动复用原错题、合并来源图、累计再次遇到次数；API 36 Room 真测验证两次采集最终只有一个错题且命令重放不重复累计。相似/条件变化题仍不自动合并 | API 36 模拟器精确重复 `1/1` 通过 | `PARTIAL`；精确重复已关闭，相似题对照与条件修订仍缺 |
| `M1-HR-05` | 提示/答案揭示 × 提交并发 × UI 崩溃 | M1 | 数据库唯一揭示/竞态测试、Tutor/Review 完整命令精确重放测试，以及设备上的跨 PracticeUnit 状态/请求身份隔离测试已在本轮全套复跑 | 强杀 UI/进程协议无记录 | `PARTIAL`；自动事务、重试载荷与跨题隔离覆盖通过，强杀协议仍缺 |
| `M1-HR-06` | Attempt 已提交 × projector 崩溃 × planner 启动 | M1 | `StudyDatabaseInstrumentedTest.projectionCommitUsesExactPrefixHeadAndStateVersionCas`、`BlockingLearningCoreReviewTest.projector commits continuous valid prefix and stops at a gap` | 各持久边界故障注入无记录 | `PARTIAL`；缺完整崩溃矩阵 |
| `M1-HR-07` | 高掌握 × 非同构突然错 × 立即重试正确 | M1/M3 | `LearningProjectorTest.independent error after high mastery enters conflicted state`、`BlockingLearningCoreReviewTest.conflicted mastery recovers after two new supported independent families and days` | 跨题族非同构金标无记录 | `PARTIAL` |
| `M1-HR-08` | API 超时 × Key 失效 × 返回设置 | M3 | 本地已区分配置/鉴权失败与发送许可/授权指纹失效：前者进入设置后返回同一任务并复用原请求，后者只重新确认本次发送；持久 `ModelTask` / `TUTOR_RESPOND` 保留原回合 | 尚无用户真实 Provider 上“超时 → Key 失效 → 返回设置 → 原回合续跑”的人工全组合记录 | `PARTIAL`；本地恢复切片存在，真实服务商组合未验收 |
| `M1-HR-09` | PDF 批量 × 损坏页 × 低电/Worker 回收 | M3/M4 | 2–30 页 PDF 本地渲染、逐页落盘、暂停/继续、失败页重试/跳过已有实现与专项测试；低电、Worker 回收和进程强杀组合仍未覆盖 | 无组合验收记录 | `PARTIAL` |
| `M1-HR-10` | 时区切换 × 系统时间回拨 × 通知重复 | M1/M2/M4 | `ReviewReminderCoordinatorTest` 已覆盖同一提醒重复广播、前进一天后回拨到已提醒日期不重发、切换到另一时区的不同本地日期只发送一次；`DataStoreReviewReminderRepositoryTest` 覆盖投递标记跨仓库重建仍有效，且 16 个并发重复声明只有一个成功；另有既有 clock rollback 和 `RoomBackedStudyExperienceRepositoryTest.reviewAnswerAtomicallyAdvancesAndReplaysAcrossLocalMidnight` | JVM 组合已通过；API 36 模拟器 DataStore `3/3`、真实通知 `1/1` 已通过；厂商省电策略与真机跨多时区长期运行仍无记录 | `PARTIAL`；本地确定性边界已覆盖，剩余为真实系统调度验收 |
| `M1-HR-11` | 未满 14 岁 × localFirst × 自定义 Base URL | M4 | 无年龄门、用例门与网关三层组合测试 | 无记录 | `NOT-IMPLEMENTED` |
| `M1-HR-12` | 旧备份恢复 × Worker 迟到提交 × 删除全部 | M4 | 无 generation/Worker/删除全部组合测试 | 无记录 | `NOT-IMPLEMENTED` |
| `M1-HR-13` | 200% 字体 × 横屏 × 单手复习 | M4 | 无专项自动化 | `design-qa.md` 只记录常规视口与 48dp，不覆盖本组合 | `MANUAL-PENDING` |

M1 退出门禁仍是整体门禁：即使上表某个自动化测试再次通过，也不能替代飞行模式真实闭环、跨持久点故障注入、双 flavor 构建/lint 和可重复人工验收。

### 5.1 M2 单题采集切片追踪

| 蓝图场景 | 本轮自动证据 | 当前裁决 |
|---|---|---|
| `BP-CAP-02` 课后收录一题 | 私有 URI → canonical asset → 可修订结构化候选 → 原子正式入库；正常候选直接继续，用户发现题面问题时再进入修改 | `IMPLEMENTED-SLICE` |
| `BP-CAP-09` 模糊/歪斜/遮挡 | 只有字节、尺寸、像素与解码失败关闭；没有画质评分、透视或截断诊断 | `PARTIAL` |
| `BP-CAP-10` 手写与批注覆盖 | 已持久化 `WritingLayer` 并提供按需修改入口；没有区域级自动分层或真实模型质量评测 | `PARTIAL` |
| `BP-CAP-11` 相册与整卷导入 | Photo Picker 已统一复制到私有缓存并走同一 Draft/Commit 管线；多张照片或 2–30 页 PDF 可一次选择、逐页保存并按稳定顺序恢复，支持暂停/继续及失败页重试/跳过；单页 2–12 道独立题可自动拆成独立草稿；整卷相邻页由模型做三态判断，本地仅对明确同题执行原子合并，歧义和失败保留原页，不要求学生手工合并或重排；错题本当前筛选结果可直接批量生成 A4 PDF | `PARTIAL`；真实 Provider 跨页判断质量、强杀/授权过期组合和超百题自动分卷仍缺 |
| `BP-CAP-12` 重复或条件变化 | 精确像素去重和独立草稿已测；不同照片只要最终结构化题面完全一致，就自动复用原错题、附加新来源图并提高既有原题的复习优先级，不新增学生步骤。模型仍可从本地缩排候选提出 `POSSIBLE_DUPLICATE` / `VARIANT_OF` 等关系；相似或条件变化题不会自动合并，尚无并排比较、合并/保留两份或条件修订专用裁决 UI | `PARTIAL` |
| `BP-CAP-15` 低内存 | 解码有 `1600 万`像素上限并将 OOM 失败关闭；没有低内存设备矩阵或后台重处理 | `PARTIAL` |
| `BP-CAP-16` 空间不足 | 写入失败保留临时输入以便重试，正式来源不自动删除；没有拍前空间预警与存储故障注入 | `PARTIAL` |

## 6. 用户习惯证据治理

| 等级 | 准确定义 | 升级所需证据 | 允许的文案 |
|---|---|---|---|
| `ASSUMED` | 团队基于经验、竞品或设计推理提出，尚无目标用户观察 | 无；所有新习惯条目默认从此开始 | “假设”“推荐路径”“待验证”，不得写“用户通常/高中生习惯” |
| `OBSERVED` | 在记录了日期、样本、任务和环境的研究中观察到行为，但未达到预设量化门槛 | 链接研究记录，写明样本数、招募条件、观察次数和反例 | “在该样本中观察到”，不得外推为普遍规律 |
| `VALIDATED` | 预先声明的目标、指标和阈值在目标人群与目标设备上通过可复现实验 | 协议、原始计数/分母、样本分层、日期、版本、阈值和结果链接齐全 | 可在明确适用范围内作为产品基线 |

治理规则：

1. 模拟器点击、设计师自测、自动化 UI 测试和竞品截图不能把用户习惯升级为 `OBSERVED`。
2. `OBSERVED` 不能仅凭主观总结升级；必须先冻结指标和阈值，再采集 `VALIDATED` 证据。
3. 每条习惯在表格中单独标级；不得用一项研究给未覆盖条目批量升级。
4. 证据过期、目标人群改变或流程发生实质变化时降回 `ASSUMED`/`OBSERVED`，并保留原因。
5. 当前 M1 合同与信息架构中的“高频最短路径/用户习惯”条目均为 `ASSUMED`；`8–12` 与 `30` 名研究仍是未来门禁，不是已完成事实。

## 7. 更新纪律

- 新增或拆分场景时，先在所属权威文档分配完整 ID，再更新本注册表的映射或追踪；禁止只在测试名中发明 ID。
- 自动化测试改名、删除或改变断言后，同一变更必须更新第 5 节；测试文件存在但断言不覆盖风险时只能标 `PARTIAL`。
- 任何 `VALIDATED`、里程碑“通过”或“已实现”声明都必须附本次版本的执行日期、命令/协议、结果和可定位证据。
- M1–M4 边界改变时，先改 M1 合同，再同步本注册表和蓝图；蓝图不得先行改写验收含义。
