# 真实复杂题图采集规则

这个清单用于建立私有验收集，不把学生原图、试卷答案或第三方受限素材提交到 Git。

## 采集池

1. 用户拥有或明确提供的中文高中真实错题扫描，只放在 D 盘私有数据目录；仓库清单仅保存相对路径、哈希和匿名标注。
2. 有明确开放许可的教材图形题，用来补齐跨科、跨图形族覆盖。首选 OpenStax 的 CC BY 4.0 内容，并逐图保存作者、页面、原始 URL、许可和是否裁剪/改写。
3. 官方公开试卷只有在许可或本地研究使用边界明确时进入私有集；不把题面、答案或图片转存到公开仓库。

## “复杂”门槛

候选至少满足两项：

- 含多个需要保持拓扑关系的对象、区域、端口、管路、导线或连接；
- 含空间方向、遮挡、投影、周期边界、转轴、剖面或多个同步视角；
- 含两条以上曲线、双纵轴、区间、辅助线或需要读数的标记点；
- 含状态变化、粒子/流体迁移、场变化、机械运动或时间顺序；
- 图文条件相互依赖，只看文字或只看图都不能完成当前小问；
- 原图有真实拍摄噪声、批改痕迹、倾斜或复杂排版，但核心关系仍可人工确认。

纯公式、单一直线图、只有一个箭头的示意图和可由普通 Markdown 完整表达的题不计入复杂图形配额。

## 首批开放许可候选来源

- OpenStax Physics 前言及许可：<https://openstax.org/books/physics/pages/preface>
- 电磁感应与线圈空间关系题：<https://openstax.org/books/college-physics/pages/23-problems-exercises>
- 电化学装置与电极电势：<https://openstax.org/books/chemistry-2e/pages/17-3-electrode-and-cell-potentials>
- 电化学综合练习：<https://openstax.org/books/chemistry-2e/pages/17-exercises>
- 化学平衡图文练习：<https://openstax.org/books/chemistry-2e/pages/13-exercises>
- 生物结构图视觉问题：<https://openstax.org/books/biology-2e/pages/3-critical-thinking-questions>
- 生物 AP 综合图形问题：<https://openstax.org/books/biology-ap-courses/pages/34-critical-thinking-questions>
- OpenStax 官方许可说明：<https://openstax.org/license/>
- CC BY 4.0 条款：<https://creativecommons.org/licenses/by/4.0/>

页面许可不自动覆盖页面中单独署名或受限的第三方图片。采集器必须逐图读取 caption/credit；许可不明确时只记录候选 URL，不下载图片。

## 分布要求

- 发布集不少于 120 个不同图像哈希、九科和十二类图形。
- 任一来源不超过总量 20%，任一图形族不超过 25%，避免模型记住固定排版。
- 同一题可以有多个小问标注，但发布门槛的“不同图片”仍按图像哈希计数。
- 至少三分之一来自真实手机拍摄或扫描，至少三分之一包含三个以上相互关联的视觉对象。
- 测试、调参和最终留出集按原始文档/试卷分组切分，不能把同一题的裁剪版本分到不同集合。

## 防止针对样本优化

- 运行时协议只接受通用结构化命令；生产源码禁止读取题号、文件名、图像哈希、基准 case ID 或来源域名来选择渲染路径。
- 新增原语必须是基础几何能力，或能在三个独立场景、两个知识主题中复用。
- 每次验收记录 schema/compiler/model/provider 版本；失败样本可以进入训练或修复集，但永远不能继续留在最终留出集。

