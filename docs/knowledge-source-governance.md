# 高中知识资料来源与使用边界

## 目的

这份登记只供知识内容制作、审校和发布门使用，不出现在学生端。它解决两个不能混为一谈的问题：

1. **内容依据是否可靠**：这份资料能否用于确定高中范围、知识名称、学习顺序或讲解方法。
2. **资料表达能否进入应用**：是否允许复制节选、改编后分发，还是只能阅读后独立归纳。

来源权威不等于拥有复制许可；资料开放也不等于符合中国高中课程范围。任何内容包都必须同时通过课程适配、学科审校和内容使用许可三道门。

## 内容身份

知识内容包允许：

- 概念解释、定理条件、推导过程和适用边界；
- 通用解题方法、题型识别方法、表示方法和实验方法；
- 典型例题、完整题面、完整答案及逐步解答；
- 常见混淆、易漏条件和多种解法之间的比较。

知识内容包不得获得下列题库身份：

- 可布置练习编号、学生作答入口或正确答案键；
- 分值、难度、评分规则、作答记录或能力证据；
- 每日复习、到期时间、推荐题或练习队列身份。

因此，“有例题、有答案”仍可能是讲解资料；只有内容被系统当作一道可做、可评、可排期的题时，才越过题库边界。

## 本地数据策略

每个来源除 `sourceType` 与 `licenseStatus` 外，还必须分别记录
`contentUsePolicy` 和 `modelUsePolicy`。前者约束应用中可保存的表达，后者约束能否把来源
原文放进检索上下文或发送给大模型；“可以阅读或改编”不自动等于“可以交给模型”。

| 使用策略 | 允许进入应用的内容 |
|---|---|
| `REVIEWED_SYNTHESIS_ONLY` | 审校者独立重写的知识、方法和例题结构；不复制来源表达 |
| `EXCERPT_ALLOWED` | 在明确许可、许可地址和署名齐全时，可使用必要节选 |
| `ADAPTATION_ALLOWED` | 在明确许可、许可地址和署名齐全时，可保存经审校的改编内容 |

`REFERENCE_ONLY` 来源只能使用 `REVIEWED_SYNTHESIS_ONLY`。从联网搜索进入审校队列的来源也一律先按该策略处理；搜索服务声称“官方”“开放”或“已授权”不能自动提升复制权限。

| 模型使用策略 | 模型可见内容 |
|---|---|
| `DERIVED_CONTENT_ONLY` | 仅允许模型读取已经独立重写、学科审校并发布的本项目内容；来源原文、截图、PDF 和长节选不得进入提示词、嵌入索引或检索上下文 |
| `REVIEWED_EXCERPT_ONLY` | 只有明确许可覆盖模型使用、且逐段审校的必要节选可以进入检索上下文 |
| `FULL_CONTENT_ALLOWED` | 只有明确许可同时允许保存、改编和模型处理时才能使用；不得由“公开可下载”推断 |

`REVIEWED_SYNTHESIS_ONLY` 与 `REFERENCE_ONLY` 必须搭配
`DERIVED_CONTENT_ONLY`。任何允许原文进入模型的记录都必须有明确许可证、许可证地址和署名，
并通过单独审计。

本地授权清单按“精确文档地址”或“明确以 `/` 结尾的路径子树”登记，不接受整站主机授权。同一个平台可以同时托管不同作者、不同版本和不同许可证的资料；一本书获得许可不能顺带授权同站其他书。

旧数据迁移到 Room v28 时统一落为 `REVIEWED_SYNTHESIS_ONLY`，不会因为历史上写过 `PUBLIC_OFFICIAL` 或 `LICENSED` 而静默获得正文复用权。

## 当前来源登记

机器可读登记位于
`core/data/src/main/resources/knowledge/source-register-2025-v1.json`。登记把
“发现到来源”“只核对了元数据”“已经取得但未审校”“正文已取得并审校”分成不同状态；
解读文章、目录或实施通知不会获得课标正文的覆盖权。

| 来源 | 用途 | 默认策略 | 当前裁决 |
|---|---|---|---|
| 教育部《普通高中课程方案和语文等学科课程标准（2017 年版 2020 年修订）》官方附件 | 2020 基线正文候选、九科范围与术语回归 | `REVIEWED_SYNTHESIS_ONLY` | 官方 ZIP 地址和长度已核对；整包哈希、逐科抽取与审校未完成，状态为 `METADATA_VERIFIED` |
| 课程教材研究所 2025 日常修订版解读专刊目录 | 九科修订版存在性、学科作者和页码核验 | `REVIEWED_SYNTHESIS_ONLY` | 已取得并校验文件哈希，但它只是解读目录，不能作为课标正文或覆盖证据 |
| 《普通高中课程标准日常修订版（2017 年版 2025 年修订）》九科正文 | 当前覆盖基线 | `REVIEWED_SYNTHESIS_ONLY` | 已从教育机构镜像取得九科 PDF，完成封面、目录、关键章节、页数和 SHA-256 身份核验，状态为 `ACQUIRED_UNREVIEWED`；镜像不是教育部原始发布地址，逐条课程映射和人工审校仍未完成，不能通过覆盖门 |
| 国家中小学智慧教育平台电子教材元数据目录 | 发现九科教材版本、册次、资源 ID 和提供者 | `REVIEWED_SYNTHESIS_ONLY` + `DERIVED_CONTENT_ONLY` | 已取得并核验目录版本及四个分片，共 3229 条教材元数据；高中九科均有记录。目录只证明可选教材身份，不获得教学参考、方法或完整例题证据权；具体教材仍需逐册审校，禁止整包复制或模型摄入 |
| 国家中小学智慧教育平台课程活动元数据目录 | 定位九科具体讲解课、方法课和例题课候选 | `REVIEWED_SYNTHESIS_ONLY` + `DERIVED_CONTENT_ONLY` | 已取得并核验目录版本及 18 个分片，共 17005 条唯一活动，其中高中 4134 条、九科均有记录。活动名称只能帮助固定待审资料；目录不等于课程正文已审校，也不参加教学参考、方法或完整例题发布门计数 |
| 人民教育出版社及其他教材出版机构 | 教材术语、章节结构和典型教学路径 | `REVIEWED_SYNTHESIS_ONLY` | 人教版高中九科教材体系和九科总复习用书系列已经完成元数据核对；未取得本项目书面许可前只登记定位并独立归纳，逐科逐册审校前不计入教学证据 |
| 学科网、菁优网及其他教培资料 | 补充细知识、方法模型、常见变式和易混边界 | `REVIEWED_SYNTHESIS_ONLY` | 学科网与菁优网九科入口均已登记为第三方候选；不复制题库、正文或答案库，只可逐份审校后独立归纳 |
| OpenStax 逐书逐版本资料 | 数理化生方法模型、完整例题和解答路径的交叉核验 | `REVIEWED_SYNTHESIS_ONLY` + `DERIVED_CONTENT_ONLY` | 数学、物理、化学、生物学四册候选已登记到具体书；CC BY-NC-SA 4.0 之外，当前书页还明确禁止未经许可把书用于大模型训练或摄入生成式 AI，因此原文不得进入本项目模型上下文；仍未通过中国高中课程映射与逐节审校 |

## 当前可核验依据

- [教育部 2020 年普通高中课程方案和课程标准通知](https://www.moe.gov.cn/srcsite/A26/s8001/202006/t20200603_462199.html)
- [课程教材研究所 2025 日常修订版解读专刊目录](https://www.ictr.edu.cn/Uploads/File/2025/12/01/2025%E5%B9%B412%E6%9C%88%E7%9B%AE%E5%BD%95.20251201190727.pdf)
- [课程教材研究所普通高中课程标准日常修订版专题解读活动](https://www.ictr.edu.cn/curriculum_reform/ke/detail/6142.html)
- [山东省济南第十一中学国家课程页（九科 2025 修订版正文镜像发现页）](https://jnsyz.jinan-edu.cn/col/col422/index.html)
- [教育部《国家智慧教育平台数字教育资源入库出库管理规范》](https://hudong.moe.gov.cn/srcsite/A16/s3342/202407/t20240703_1139249.html)
- [福建省教育厅 2026 年教辅送评通知（明确采用 2025 日常修订版并要求依法授权）](https://jyt.fj.gov.cn/xxgk/gggs/202603/t20260313_7109906.htm)
- [人教社第十一套普通高中教科书介绍](https://www.pep.com.cn/xw/zt/rjwy/rjsd11tgzjksjs/)
- [人教版高中总复习优化设计九科目录](https://www.pep.com.cn/jxzy/ztjf/yhsjzfx/gz/)
- [菁优网高中九科资源入口](https://www.jyeoo.com/history2/report/search?ed=&so=6)
- [OpenStax 当前教材许可说明](https://help.openstax.org/s/article/Licensing-information-of-OpenStax-textbooks)
- [Creative Commons BY-NC-SA 4.0 许可摘要](https://creativecommons.org/licenses/by-nc-sa/4.0/)

这些链接只证明来源身份、实施基线或许可规则，不证明本项目已经完成九科内容审校。完整覆盖仍必须由内容包、逐节点来源定位、教学支持材料数量和人工质量评测共同证明。

## 发布门

发布前至少执行：

```powershell
& .\tools\audit-smartedu-textbook-catalog.ps1 -RequireNineSubjects
& .\tools\audit-smartedu-lesson-activity-catalog.ps1 -RequireNineSubjects
& .\tools\audit-knowledge-source-rights.ps1
& .\tools\audit-knowledge-source-register.ps1
& .\tools\audit-knowledge-coverage-ledger.ps1 -RequireComplete
& .\tools\audit-knowledge-packs.ps1 -RequireFullCoverage
```

第一条验证当前电子教材目录的版本、分片、唯一资源 ID 与九科学科存在性；第二条验证课程活动目录的版本、分片、唯一活动 ID 与九科学科存在性，但不把目录计作教学内容证据；第三条验证讲解资料的使用许可与派生方式一致；第四条验证每科的当前课标正文证据、
至少两个独立教学参考来源、方法参考和完整例题参考都已经取得并审校；第五条验证独立于内容包的九科范围清单已经逐科审校；第六条再验证当前
2025 基线下九科目录与范围清单逐点一致，且教学支持内容已声明并实际达到完整覆盖。教学支持声明为 `FULL`
时，每个细化知识点都必须绑定讲解资料，而且九科各自至少有方法模型和完整例题。
任一失败都不能对外宣称“覆盖全高中知识”。
