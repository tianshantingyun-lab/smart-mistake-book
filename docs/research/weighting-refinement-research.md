# 加权机制深化研究（2026-08-29 实施轮）

**地位**：本文是 spec《mastery-scheduling-spec.md》§2.5/§2.11/§2.12/§2.14/§6 五处算法细化的证据底稿。全部机制保持"现有链路上的提升"，FSRS 公式内部（D/稳定性更新）不动。

---

## 一、自评通道的过自信与静态上限（spec §2.5）

**证据**
- Dunlosky & Rawson (2012), *Learning and Instruction* 22(4):271–280, doi:10.1016/j.learninstruc.2011.06.002：119 名学生学习 27 个生僻词，JOL（学习判断）过自信者更早停止学习、两周后保持更差；控制前测拼写水平后关系仍成立。
- 同组早前研究（ERIC ED490313）：86% 学生对自己拼写生僻词的能力过自信。
- 机制背景：元记忆监测依赖线索利用（Koriat cue-utilization），自评的线索（流畅感）与提取成功弱相关。

**算法决定**（用户拍板：静态上限 + 数据校准）
1. `FsrsEvidenceRatingMapper` 拆双函数：
   - `schedulingRatingFor`（喂 FSRS/LearningProjector）：主观 `SELF_REPORTED_RECALL` 一律封顶 **Good**——"很轻松"（0.9）不再拿 Easy 奖励（w16×1.87 的稳定性加成），防止过自信直接放大 S。
   - `reportedRatingFor`（review_log 记账）：忠实记录用户按的键（4=很轻松），证据原值不丢，优化器与未来校准可回溯。
2. 非主观理由（视觉满足=HARD 等）两函数一致，行为不变。
3. `SourceCalibration`（harness 新增）：对每个主观来源，统计"该来源正性报告 → 同卡下一次真实作答"的实际回忆率，对照真实作答基线。**≥30 对**且低于基线 **≥0.15** 时输出降档建议——只建议，人工审批后才改映射表（禁止自动改写权威映射）。

## 二、切屏/注意力转移（spec §2.14，本轮新增）

**证据**
- D'Mello, Mills, Bixler & Bosch (2013), *Psychonomic Bulletin & Review* 20(6):1286–1295：走神率随文本难度**上升**（难文本更容易走神）——支持"高频切屏=题目让学生难受/不熟"的解读；同组 Applied Psychology 2013 论文：走神率解释理解成绩约 **18% 方差**。
- Craik, Govoni, Naveh-Benjamin & Anderson (1996), *JEP: General* 125(2):159–180：**编码期**分心显著损伤后续记忆（分心的代价主要落在编码）。
- Sana, Weston & Cepeda (2013), *Computers & Education* 62:24–31：多任务组理解成绩低 ~11%。
- Trafton et al. (2005)：中断后恢复有代价，恢复成本随时长增长——支持按累计离开时长分级。

**采集**（本轮已落地，全程静默）
- `ReviewInteractionTracker`：ON_PAUSE 计中断次数并记时刻，ON_RESUME 累计 `awayMillis`；`onEdit()` 钩子就绪（edit_count 闭环点）。
- v38：review_log 增 `away_millis`（累计离开毫秒）与 `planned_reason`（选题理由快照）；随三类提交入账。

**算法决定**（用户拍板：权重折价 + 重教信号，不动 FSRS 公式）
1. `AttentionSignal.attentionFactor(switches, awayMillis)` = 1 − 0.12·max(0, switches−1) − 0.05·⌊away/30s⌋（away 最多计 8 档），下限 0.6。**首次切屏豁免**（单次短暂切出属常态）。系数是工程先验，明示待 review_log 数据校准。
2. 应用点：选择流与主观通道的证据权重 ×= factor（主观通道再乘时段乘数）。折价通过 mapper 的**低置信答对降档**对真实作答生效：`INDEPENDENT_CORRECT` weight < 0.85 → HARD（0.88=1 次加档仍 Good；0.76 起 Hard）——对应 §2.5"高置信答对→4"的镜像。
3. `isAvoidanceSignal(switches≥2 && rating≤2)`：近 30 天内出现 ≥2 次的卡 → `ReviewReason.AVOIDANCE_SIGNAL` + 选题权重 +1.0（双 planner），把它推向重教通道而不是单纯再排期。

## 三、时段乘数接线（spec §2.12，保守收缩版）

**证据**
- May & Hasher (1998), *Psychological Science* 9:368–371：同步效应——最优时段的抑制控制/线索回忆/再认更好，效应位于编码与提取。
- Brattico et al. (2025), *Journal of Sleep Research*（107 名大学生 24 小时四测）：陈述性记忆峰值在 14:00 且**与 chronotype 无关**；晚型者早晨表现并不差——混合证据，警惕人群级硬权重。
- May & Hasher 系列的效应主要在**执行控制**任务；Wieth & Zacks (2011)：非最优时段反而利于顿悟类任务。

**算法决定**（用户拍板：仅主观通道 + 提醒）
1. `TimeOfDayCalibrator` 正式接线：主观通道（自评/评级/视觉）证据权重 ×= 个人桶乘数（收缩 0.5、钳制 0.8–1.25、桶样本 <30 恒 1，冷启动零影响）；**真实作答不乘**——其正确率本身已含时段效应，再乘会循环归因。
2. 提醒峰值桶：`suggestedReminderMinute()` 返回个人峰值桶中点分钟（达标才非空），提醒设置页显示一行建议文案，不自动改用户设定。

## 四、选题权重的数据标定设施（spec §6，权重数值本轮不动）

- v38 `planned_reason` 把计划理由快照随作答写入 review_log，使"理由→实际回忆率"增益分析可行。
- harness 的 `calibrateSources` 是第一张标定表（按来源）；per-reason 增益复用同一 join 口径（reason 快照列）。
- **重标定程序**（写入 spec §6）：≥200 条样本触发分析；保持单调序（due > weakness > 重错≈考前 > 等待/lapse）；只允许人工审批后改常量；每次改动必须在评估报告里给出前后 log-loss 对照。

## 五、优化器防过拟合（spec §2.11）

- 协议对齐 srs-benchmark：**时间序 hold-out**（80/20，按 reviewedAt 全局分位切分），优化目标改为验证集 log-loss，早停（验证损失连续 5 轮不改善即停）；返回 train/valid 双损失。
- 学习面分阶段（fsrs-rs 阈值口径，已源码核验）：<8 条回默认；8–63 仅拟合 w0–w5；≥64 拟合 w0–w14+w20。**解锁 w15/w16（HP/EB）的规则**写为：≥5k 样本且验证增益 >2%，本轮不实现。
- BKT 学习率（0.32/0.42）与 EMA 半衰（7 天）不进可学习面：自由度对数据量的要求远超单机日志规模。

## 来源清单（本轮新增）
- Dunlosky & Rawson (2012), doi:10.1016/j.learninstruc.2011.06.002
- D'Mello, Mills, Bixler & Bosch (2013), doi:10.3758/s13423-013-0496-y（及其 Applied Psychology 2013 姊妹篇）
- Craik, Govoni, Naveh-Benjamin & Anderson (1996), JEP:General 125(2)
- Sana, Weston & Cepeda (2013), Computers & Education 62
- May & Hasher (1998), Psychological Science 9
- Brattico et al. (2025), Journal of Sleep Research
- Wieth & Zacks (2011), 时间与顿悟问题解决
- Trafton et al. (2005), 中断恢复成本
- Zhang, Morey & Mayr (2024), Psychonomic Bulletin & Review（媒体多任务与记忆元分析）
