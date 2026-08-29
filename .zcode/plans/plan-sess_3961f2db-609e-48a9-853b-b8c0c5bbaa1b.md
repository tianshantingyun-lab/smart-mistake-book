# 加权机制深化 + 切屏注意力信号 实施计划

## 研究依据（已检索取证，将落文档 docs/research/weighting-refinement-research.md）
- **自评过自信**：Dunlosky & Rawson 2012（86% 学生 JOL 过自信→提前停止学习→两周后保持更差）→ 主观通道上限策略
- **切屏=难度/厌恶信号**：D'Mello et al. 2013（走神率随文本难度上升；走神率预测 ~18% 理解方差）
- **分心损伤编码**：Craik et al. 1996（编码期分心显著损伤后续记忆）；Sana et al. 2013（多任务 hindering 学习）→ 证据折价
- **时段**：May & Hasher 1998 同步效应（编码/提取在最优时段）+ Brattico et al. 2025 混合证据（晚型者早晨并不差）→ 维持保守收缩版（个人乘数、≥30 样本、不写死人群级权重）
- **可学习面**：fsrs-rs 8/64 阈值（已源码核验）+ srs-benchmark 时间序分割口径 → 优化器补 hold-out

## 阶段 A：自评置信校准（静态上限 + 数据校准）
- A1 `FsrsEvidenceRatingMapper` 拆双函数：`schedulingRatingFor`（调度用：主观 SELF_REPORTED_RECALL ≥0.9 上限 Good，不再映射 EASY；review_log 记账用 `reportedRatingFor` 不降档记 4）。Projector 用调度版，recordReviewLog 用记账版
- A2 harness 增 `SourceCalibrationReport`：按 source_kind 的"调度档≥3 → 同卡下次真实作答实际回忆率"校准表；≥30 对给出降档/回调建议（文档化人工审批，不自动改映射）
- 测试：双映射函数、校准报告

## 阶段 B：时段乘数接线（仅主观通道 + 提醒峰值桶）
- B1 repository：主观提交（自评/评级/视觉）时从 review_log 现算 `TimeOfDayProfile`，w_e ×= multiplierFor(event 桶)——≥30 样本自动生效，冷启动恒 1；真实作答不乘（其正确率本身已含时段效应，避免循环）
- B2 `suggestedReminderMinute()`：峰值桶中点分钟（达标时）；设置页显示一行建议（ReviewReminderScreen）
- 测试：乘数应用、建议分钟

## 阶段 C：选题权重标定设施（数据驱动，权重本轮不动）
- C1 v38 迁移 review_log + `planned_reason TEXT`：submitReviewChoice/submitChoice 把 queueItem 主理由写入，使"计划理由→实际回忆"增益分析可行
- C2 harness 增 per-reason/per-source 增益报告；spec §6 写重标定程序（≥200 条触发、单调序约束、人工审批常量）

## 阶段 D：优化器防过拟合
- D1 `FsrsParameterOptimizer`：全局时间 80/20 hold-out，优化目标改验证损失，早停（验证损失 5 轮不降）；返回 train/valid 双损失；阈值保持 8/64
- D2 spec §2.11 学习面分阶段表（≥5k 且验证增益>2% 才考虑解锁 w15/w16，写为规则不实现）
- 测试：合成数据 hold-out 不劣于默认参数

## 阶段 E：切屏/注意力转移落入算法
- E1 Tracker：+累计离开时长（ON_PAUSE→ON_RESUME 差值累加）+`onEdit()` 钩子（顺带就绪上轮记录的 edit_count 待接点）
- E2 v38 迁移：review_log + `away_millis`（默认 0）；Submission/端口/实体/DAO 全链路；迁移矩阵自动覆盖 1→38
- E3 `AttentionSignal.attentionFactor(switches, awayMillis)`：1 − 0.12·(switches−1) − away 每 30s −0.05，下限 0.6（系数为工程先验，明示待数据校准）；主观+真实通道 w_e 同乘（Craik 编码分心依据）
- E4 mapper 低置信答对降档：INDEPENDENT_CORRECT 且 weight<0.85 → HARD（分心/低 RT 折价由此对真实作答生效，对应 §2.5"高置信→4"的镜像）
- E5 重教信号：ReviewReason 新增 AVOIDANCE_SIGNAL；repository 从 review_log 近 30 天统计（该卡 switches≥2 且 rating≤2 ≥2 次）→ candidate.avoidance → planner 加小权重(1.0)与重教理由
- 测试：attentionFactor、mapper 降档、v38 迁移、planner avoidance

## 阶段 F：验证与回写
- 全单测 + connected（1→38 矩阵）+ 双 flavor 构建/lint + 装机 smoke
- spec §2.5/§2.11/§2.12/§2.14/§6 回写 + 研究文档 + 提交

**不做**：FSRS 公式内部不动（D/稳定性更新保持 py-fsrs 对拍基线）；BKT 学习率不进可学习面；权重常量数值不变（等 ≥200 条数据标定）。