# 知识库更新机制设计 · 2026-09-19

> **回答的问题**：软件发布之后，如何更新知识库——覆盖**所有**更新场景，不破坏学生数据。
> **依据**：内部实测（安装器 / 外键 / 逐字比较字段）+ 外部四条线的交叉验证（出处见 §5）。
> **性质**：设计，不含施工。三个必须由产品决定的分叉在 §6。

---

## 1. 结论

**最优方案：把"身份"与"内容"彻底分开——稳定 ID 永久不变，内容按版本调和（reconcile），删除落墓碑，实质改变走"新建 + 取代"，退役置状态。**

三条原则：

| # | 原则 | 一句话 |
|---|---|---|
| 1 | **身份永久** | `knowledge_node_id` 一旦发布不可变、不可复用；改名不是改身份 |
| 2 | **内容调和** | 安装器从"逐行相等否则拒绝"改成"算差异 → 只 patch 差异 → 状态表在事务内推进"，崩溃后重跑收敛 |
| 3 | **永不物删** | 删除 = 墓碑（含 `supersededBy` 重定向）；合并 = 重定向；退役 = 状态位 |

这三条不是自创——**1EdTech CASE 1.1 已经把它们写成了教育标准版本化的规范级规则**（§5.1），而 Kolibri（另一个离线教育 App）在工程上就是这么实现的（§5.2）。

---

## 2. 全场景枚举（用户要求"考虑到所有情况"）

`现状` 指今天会发生什么。**13 个字段全在比较范围内，所以任何字段改动都会拒绝。**

### A. 节点内容

| # | 场景 | 现状 | 方案 |
|---|---|---|---|
| A1 | 改名称 | **拒绝**（`displayName` 在比较内） | 原地更新，ID 不变；外部有明文依据（CASE §4.3.4"改字段、不改 GUID"） |
| A2 | 改别名 | **拒绝** | 原地更新；别名是检索特征来源，改完需重建 `knowledge_search_feature` |
| A3 | 改边界 boundary | **拒绝** | 原地更新 |
| A4 | 改 kind / granularity | **拒绝** | 原地更新；但 kind 影响检索排序，需回归评测 |
| A5 | 改 sourceLocator | **拒绝** | 原地更新 |
| A6 | **新增节点** | **拒绝** | 插入；`createdAt` 落库 |
| A7 | **删除节点** | **拒绝**（且外键 RESTRICT 挡住真删） | **墓碑**：置 `RETIRED`，不物理删 |
| A8 | **合并 A←B** | 无法表达 | B → `RETIRED` + `supersededBy=A`；读路径把 B 解析到 A |
| A9 | **拆分 A→A₁,A₂** | 无法表达 | A → `RETIRED`；A₁A₂ 新建。**历史证据不自动分配**（见 §6 分叉 1） |
| A10 | 改 slug | **等于换 ID**：旧节点留着、学生数据挂旧 ID、新节点从零 | **禁止**。要改语义就走 A9（新建 + 取代），不是改 slug |
| A11 | 改 stableCode | 拒绝（且唯一索引冲突） | 禁止；stableCode 与 ID 同级不可变 |
| A12 | 改 verificationStatus | 拒绝 | 可原地更新；`MODEL_CANDIDATE` → 人工确认是正常流转 |

### B. 树结构

| # | 场景 | 现状 | 方案 |
|---|---|---|---|
| B1 | 改父节点（re-parent） | **拒绝**（`parentKnowledgeNodeId` 在比较内） | 原地更新；子树不受影响（ID 不变） |
| B2 | 新增分组 topic | 拒绝 | 插入（topic 也是节点，同 A6） |
| B3 | 删除分组 topic | 拒绝（RESTRICT） | 墓碑；子节点须先 re-parent |
| B4 | 改 topic 名 | 拒绝 | 原地更新（与我做的层内名收敛同类） |
| B5 | 整体重构树 | 无法表达 | 一组 re-parent + 一组墓碑，在一个调和循环里完成 |

### C. 关系

| # | 场景 | 现状 | 方案 |
|---|---|---|---|
| C1 | 新增前置边 | 关系导入**不受 require 保护**，可能重复插 | 按 (prereq, dep, type) 做 diff upsert |
| C2 | 删除前置边 | 同上 | 按主键删除（关系表无学生数据，可物删） |
| C3 | 改边方向 | 同上 | 先删后插，同一事务 |

> **注意 C1-C3 是当前唯一"能写进去"的一类**——`importKnowledgeNodeRelations` 在 `require` 之外无条件执行。这既是漏洞也是线索：它证明带主键的关系表天然可幂等 upsert。

### D. 材料与来源

| # | 场景 | 现状 | 方案 |
|---|---|---|---|
| D1 | 新增材料 | **拒绝**（材料侧也逐行相等） | 按 `materialId` upsert |
| D2 | 改材料内容 | 拒绝 | 原地更新（`contentFingerprint` 会变，需同步） |
| D3 | 删除材料 | 拒绝 | 墓碑（`RETIRED`）——学生可能正在复习用它生成的题 |
| D4 | 改绑定（换/解/加绑） | 拒绝 | 按 (materialId, nodeId) diff |
| D5 | 新增来源 | 拒绝 | upsert；`contentFingerprint` 有唯一索引，天然去重 |
| D6 | 改来源元数据 | 拒绝 | 原地更新 |

### E. 包与分发

| # | 场景 | 现状 | 方案 |
|---|---|---|---|
| E1 | 同 packId 内容变更 | **整包拒绝** | 调和循环；这是主路径 |
| E2 | 换 packId | 所有 ID 变 → 学生数据全成孤儿 | **禁止**。packId 必须稳定 |
| E3 | 侧车增卷（容量） | 已解决（索引文件驱动） | 沿用 |
| E4 | **独立于 app 版本更新** | 不可能（包在 APK 里） | 见 §6 分叉 2 |

### F. 学生数据状态（每种内容改动都要过这 6 关）

| 状态 | 存在的问题 | 方案如何保住它 |
|---|---|---|
| 该节点有错题绑定 | 外键 RESTRICT，删不掉 | 墓碑保留行 → 外键永不失效 |
| 有掌握度 | `knowledge_mastery_state` 是 **CASCADE**，真删会静默带走 | 同上；**这是"永不物删"最硬的理由** |
| 在复习队列 | `review_queue_knowledge_node` RESTRICT | 墓碑节点从新计划里排除，历史计划行保留 |
| 有 quiz 记录 | 引 `knowledge_node_id` | 同上 |
| 用户自建节点（`USER_CONFIRMED`） | 与包内节点混在同一张表 | 调和循环**只动包内对象**，不碰用户产物 |
| 已归档错题 | 绑定仍在 | 同上 |

---

## 3. 机制设计（四条）

### 3.1 数据模型：加生命周期字段，照抄 CASE

`knowledge_node` 增列（都可空，向后兼容）：

```
status            DRAFT | ACTIVE | RETIRED     （对应 CASE 的 adoptionStatus）
status_start_at   生效时间
status_end_at     退役时间
superseded_by     被谁取代（合并/拆分时的重定向目标）
content_hash      内容指纹；调和循环用它判断"是否要更新"
```

**关键**：`knowledge_node_id` 与 `stableCode` **不动**。学生数据引用的永远是它们。

材料表同构加 `status` / `content_hash`。

### 3.2 安装器：从"逐行相等"改成调和循环

外部有现成范式，三处独立来源指向同一形态（Kubernetes 控制器 / Terraform / dbt+Liquibase，见 §5.3）：

```
期望态 = 随包内容（节点 + 边 + 材料 + 来源），每对象带 content_hash
实际态 = knowledge_node / material / relation 表
状态表 = content_install_state（已应用的包版本 + 每对象 hash）

每轮调和（幂等，可重复执行）：
  1. 读实际态
  2. 逐对象 diff → insert / update / tombstone 三类动作
  3. 只 patch 观测到的差异
  4. 状态表在同一事务内推进
  5. 崩溃后重跑同一循环即收敛

关键性质（Kubernetes declarative 原文）：
  "retains changes made by other writers ... by using the patch API
   operation to write only observed differences"
  → 天然不会覆盖学生的自建节点
```

**这解决了 KD-15 那一类问题**：一处时间戳倒挂不会再让整包停摆——每对象独立判定，一个对象坏只影响它自己。

### 3.3 删除/合并/拆分：墓碑 + 重定向

| 动作 | 实现 |
|---|---|
| 删除 | `status=RETIRED`，行保留，`superseded_by=null` |
| 合并 A←B | B → `RETIRED` + `superseded_by=A` |
| 拆分 A→A₁,A₂ | A → `RETIRED`；A₁A₂ 新建 |

**读路径一律过滤 `status != RETIRED`**（检索候选、整理提示词、复习计划），**历史读路径不过滤**（错题详情、掌握度列表要能解释旧记录）。

**墓碑的已知代价**（外部有明文，见 §5.4）：会持续累积、需要保留期策略、且"复用被退役的 slug"会产生重建问题。**CASE 直接规定"绝不复用"**，本项目照抄。

### 3.4 分发：随版本，且内容与代码解耦

外部结论明确：**Android 官方没有"内容独立于 App 版本热更新"的机制**（§5.5）。Play Asset Delivery 跟随版本更新、无 Play 不可用、侧载更新还要求卸载重装。

所以：
- **本期只做"随版本更新"**——上面的调和机制就够了
- **但要做一件事**：把内容与 Kotlin 解耦到底。今天加一个侧车已经不用改代码（索引文件驱动），把这个原则贯彻到"加一科 / 加一个包"也不改代码

---

## 4. 方案如何覆盖 §2 的每一个场景

一句话验证：**A1-A5、A12、B1、B4、C1-C3、D1-D6、E1 全部走 `update` 或 `upsert`；A6、B2 走 `insert`；A7-A9、B3、B5、D3 走 `tombstone`（+ 重定向）；A10、A11、E2 列为禁止。学生数据因为引用的是永不变更的 ID、且退役行永不物理删，六种状态全部自然保住。**

---

## 5. 外部依据（逐条出处）

### 5.1 1EdTech CASE 1.1 —— 教育标准版本化的规范级规则（最强依据）
- **发布后 ID 不变**：`"once a framework of competencies has been 'officially published', the identifiers established to represent the competencies should not change."`
- **小改**：改字段、不改 GUID，更新 `lastChangeDateTime`
- **实质改变含义**：**新建** CFItem/GUID，旧条目保留并弃用，**加一条 `replacedBy` 关联**
- **退役不删**：设 `statusEndDate`，可加 `[DEPRECATED]` 文案，`"this preserves the CFItems as a permanent record"`
- **版本锁定**：`Deprecated/Retired` 的文档**全部锁定为只读**，`"This is to allow historical reference"`
- 关联类型原生含 `replacedBy` / `exactMatchOf` / `isTranslationOf`
- 出处：`imsglobal.org/spec/CASE/v1p1/impl/` §4.2、§4.3.1–4.3.6、A.14；Information Model §7.4.1

### 5.2 Kolibri（离线 Android 教学应用）—— 最同构的工程实现
- **双 ID**：`content_id` 稳定、用户数据只引用它；节点 `id` 随频道版本重生。原文：`"the content_id is used for tracking a user's interaction with a piece of content, in the face of possibly many copies of that content."`
- **内容库与用户库物理分离**（`ChannelMetadataCache` 在默认库，内容库按频道可替换）
- **升级 = 算 diff**：`upgrade.py` 产出 `new_resource_content_ids` / `updated_resource_content_ids` / `resources_to_be_deleted_count`
- 出处：`learningequality/kolibri` `core/content/base_models.py`、`core/logger/models.py`、`core/content/utils/upgrade.py`

### 5.3 调和循环（reconciliation loop）—— 崩溃安全的应用模型
- **Kubernetes 控制器**：`"it doesn't matter if the overall state is stable or not"`——不追求一次事务成功，追求循环重试直到收敛
- **声明式只 patch 差异**：`"retains changes made by other writers ... by using the patch API operation to write only observed differences"`
- **Terraform** 点破本项目的病根：`"Terraform expects a one-to-one mapping ... guaranteed by Terraform being the one to create each object and record its identity in the state"`——**你们现在没有 state/identity 绑定，只能靠全量逐行比对来猜**
- **Flyway repeatable migrations**：`R__` 前缀、`"(re-)applied ... every time their checksum changes"`，官方明确用途含 `"Bulk reference data reinserts"`
- **Liquibase**：`MD5SUM` + `EXECTYPE`（`EXECUTED/FAILED/SKIPPED/RERAN/MARK_RAN`）——**失败也是一条可读状态**；`loadUpdateData` 是现成的 upsert 原语
- 出处：`kubernetes.io/docs/concepts/architecture/controller/`、`.../working-with-objects/object-management`；`developer.hashicorp.com/terraform/language/state`；`documentation.red-gate.com/flyway/...`；`docs.liquibase.com/concepts/tracking-tables/...`

### 5.4 墓碑的已知陷阱（必须一并设计）
- **会累积**：Cassandra `"accruing tombstones which will permanently accumulate disk space"`；S3 删除标记按 key 计费
- **会复活**：Cassandra 的 *zombie*（恢复晚于 `gc_grace_seconds` 的数据会复活）；S3 删掉删除标记即复活
- **保留期是唯一开关**：越久越安全、空间越大，没有免费选项
- **dbt 的教训**：`hard_deletes` 默认 `ignore` 会让删除**静默地继续被当作 current**——默认值本身是陷阱
- 出处：`cassandra.apache.org/doc/latest/.../tombstones.html`；`docs.aws.amazon.com/.../DeleteMarker.html`；`docs.getdbt.com/docs/build/snapshots`

### 5.5 Android：官方没有内容热更新机制
- **PAD 跟随 App 版本**：`"When the app is updated, install-time asset packs are updated as part of the base app update"`；且 `"All previously-downloaded asset packs are invalidated"`
- **无 Play 不可用**：`"The Play Core libraries are your app's runtime interface with the Google Play Store."`
- **侧载路径不支持更新**：`"Updates are not supported. Before installing a new version of your build, manually uninstall the previous version."`
- **预置数据库不保用户数据**：官方 Room 文档说明预置 DB 只用于首次创建或破坏性重建；版本不匹配时**直接被忽略**
- 出处：`developer.android.com/guide/playcore/asset-delivery`、`.../asset-delivery/test`；`support.google.com/googleplay/android-developer/answer/9859372`；`developer.android.com/training/data-storage/room/prepopulate`

### 5.6 同类产品的既有做法（支持"内容对象与调度对象分离"）
- **Anki**：GUID 不动就能就地更新，且原文 `"If notes are updated in place, the existing scheduling information on all their cards will be preserved."`
- **Khan Academy**：新增知识点**只抬高分母**（`"Each new skill added ... will increase the mastery goal by 100 mastery points"`），不清空既有掌握
- **Duolingo**：skill tree 改 Path 时给出 **1:1 映射**（`"One level ... is equivalent to one crown level of a skill on the old home screen"`）
- **Memrise**（负面先例）：社区课程下架，官方只能承诺"延长保留期 + 帮用户导出"
- **SuperMemo**：拆分文章产生多个独立调度元素；退役（`Done!`）**删重复历史但保留派生物作为引用源**

### 5.7 中国课改的制度做法
- 教育部教材〔2020〕3 号与 2018 年指导意见给出的是**"新版整体发布 + 按起始年级分批切换 + 旧版继续有效至学生毕业"**，从不做"知识树原地改版"
- **未找到**官方的新旧知识点映射/过渡规则文本（3 次检索均只拿到 JS 外壳）→ **映射必须自建**

---

## 6. 三个必须你定的分叉（外部的诚实边界）

### 分叉 1：KC 拆分/合并后，历史掌握度怎么算？
**外部没有标准做法。** 这一条我做了专门检索，结论是明确的缺口：

> 学习分析文献（LFA、Q 矩阵校验）讨论的是**重估模型（re-fit）**，而不是**迁移已存状态（migrate stored state）**。我在可控来源里**没有找到任何**规定"把某学生已有的掌握值/复习计划从被拆的知识点迁移到两个新知识点"的标准做法。

可选的三种处置，各有代价：

| 选项 | 做法 | 代价 |
|---|---|---|
| a. 不动（推荐起点） | 旧节点墓碑上保留全部历史证据；新节点从零开始 | 学生"学过"的感觉被重置；需要产品层明示 |
| b. 均分 | 旧掌握度按比例复制到新节点 | 凭空制造证据，且比例是拍的 |
| c. 重估 | 用历史作答在新 KC 映射上重新拟合 | 需要保留题目/作答层；外部范式来自 DataShop（"原始作答不可变，KC 是叠在其上可多版本并存的映射层"） |

**外部唯一有官方明文的支撑是 c 的前提**：DataShop 明确 KC 模型是"可导入、可比较、可并存"的映射层，原始 step 数据是锚点。**本项目现在把错题/掌握度直接锚在 `knowledge_point_id` 上，等于把映射层当成了锚点**——这是拆分/合并会污染历史的根因。

### 分叉 2：内容要不要能不发版更新？
- **只随版本走**（推荐）：§3 的机制就够。Kotlin 零改动，`strictOffline` 语义不变。
- **独立更新**：必须自建下发通道（Android 官方无此能力）。且要回答：`strictOffline` 的"完全不联网"是否包含内容更新？若要包含，两个 flavor 就不能共用同一份内容包。

### 分叉 3：内容增长与体积/启动耗时
包现在 16 MB + 6 侧车，全在 APK 里；安装器每次启动做比较。内容"越全越好"会线性推高这两项。**改调和循环后每次启动只比 hash**，能显著缓解，但 APK 体积仍随内容线性增长。要不要设一个"内容随包 + 增量下载"的混合形态，是需要你拍的。

---

## 7. 施工顺序（建议）

| 步 | 内容 | 为什么这个顺序 |
|---|---|---|
| 1 | 加生命周期字段（`status` / `supersededBy` / `contentHash`）+ Room 迁移 | 纯加列，向后兼容，不动行为 |
| 2 | 安装器改成调和循环 + `content_install_state` 表 | 核心机制；做完就能安全更新 |
| 3 | 读路径过滤 `RETIRED`（检索 / 整理 / 复习三条） | 让墓碑真正生效 |
| 4 | 生成器侧产出 `supersededBy` 与 `contentHash` | 让"合并/拆分"可表达 |
| 5 | 端到端演练：造一版差异包，在真机上验证学生数据完好 | **这是唯一能证明方案成立的验证** |

**第 5 步是不可省的**——本方案的全部价值就是"学生数据不受损"，而这一点只能靠实测证明。建议演练覆盖 §2 的每一类动作各至少一例。

---

## 附：本轮未取到的来源（不作结论）

- ASN 的跨版本映射操作规范（站点连接超时）
- AnkiWeb/AnkiHub 共享牌组更新的产品级说明
- RemNote / Quizlet / ALEKS-KST 的可引用先例
- 中国官方的新旧知识点映射文本
- SCD 术语中"维基版"与"Kimball 版"的 Type 4/6 定义**不一致**——引用时必须写明是哪一版
