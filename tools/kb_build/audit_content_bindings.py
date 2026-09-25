# -*- coding: utf-8 -*-
"""内容绑定审计器（Stage-4 · WP1）：出「可疑绑定候选」与裁定切片，**不替人裁定**。

## 它消灭的失败

两个最弱的章——物理必修第一册·第三章·相互作用（金标 4/9，MISS 5）、化学必修第一册·
第三章·铁与金属材料（4/9，MISS 5）——**都不缺料**（465 / 498 条材料、零材料节点 0），
丢的不是材料，是**材料与节点的对应关系**（绑定/归属轴）。可是绑定正确率无法用字符串
度量（登记册 I-05：宽松口径 98.1% 虚高、严格口径 16.4% 虚低，两个数都无效），
所以本工具不判分、不下裁定：它只做**机械预筛**，产出候选行交给 WP2 逐条读内容裁定。
候选 ≠ 裁定（`verdict` 列一律留空）。

## 判据（全部机械可复算）

| 代号 | 判据 | 消灭的失败 |
|---|---|---|
| C1 `own-title-detached` | 材料标题与本节点「名字＋全部别名」零词面交集 | 材料挂在一个它标题里一个字都没提的节点上 |
| C1' `own-title-alias-only` | 标题与节点**名**零交集、只在别名里挂上（别名表由绑定材料标题重建 ⇒ 自证不算旁证） | 别名污染（J-05）把「错绑」洗成「看着有依据」 |
| C2 `own-subject-detached` | 材料首句主语（判断句 `X 是/为/指…` 的 X）与本节点「名字＋全部别名」零词面交集 | 标题是面相式写法（I-05 的假阴性来源），只有正文主语还露出真身 |
| C3 `foreign-title-match` | 材料**绑在靶区之外**，但标题与靶区某节点名是 IDF 加权强匹配（≥0.50 且至少命中一个稀有词），且强于它与自己当前节点的匹配 | 靶区节点的材料被挂到别的节点上——只审靶区内部的预筛对此完全失明 |
| C4 `registered-anchor` | 登记锚点（`tables/content_audit_anchors.csv`，来源见登记册 I-04）在当前包上的逐条复核（登记时绑哪 vs 现在绑哪） | 已核实过的错绑在后续改绑 / 晋升里被静默回归，没有护栏 |

「词面」= CJK 二元组 + ASCII 词元（`trna`、`pd`、`l1`…），**不引分词器、不引模型**：
本工具只定位，不重写特征抽取。切片里的名次来自 `tools/dense_build/stage3_expectation.py`
的 `lexical_rows()`（`_rank_index()` 是它的调用方，不另写一份）。

## 报告口径（写死在这里，不在运行期由外部输入决定）

- 词面交集 = 集合交；CJK 二元组对短名/长名一视同仁，因此 C1/C1'/C2 是**弱判据**：命中只说明
  「标题/首句主语与本节点一个字都对不上」，不说明绑错（面相式标题是正常写法）。
- C3 的 IDF 在**全包 3,572 个节点名**上算（DF = 含该词元的节点数）；稀有词 = DF ≤ 3
  **且不是两字 ASCII 片段**（`fe`/`oh` 在节点名语料里 DF 极小，会把 `Fe(OH)3胶体制备`
  强匹配到 `Fe(OH)2的制备`——首版实测的假阳性）；加权分 = 命中词元 IDF 和 / 节点名词元 IDF 和。
  分高只说明「标题把节点名讲全了」，不说明归属正确。
- **C4 是复核不是指控**：登记册 I-04 的 6 条在本轮开始时**已经改绑**（登记时绑「细胞膜的结构和功能」
  等、现绑「翻译 / 细胞中的脂质 / 免疫系统的组成和功能 / 细胞壁 / 激素 / 细胞核的结构和功能」）。
  行的证据里同时写「登记时绑哪」「现在绑哪」「现绑节点是否解释得了材料」，读得出来就不必猜。
- 同一材料可能出多行（不同判据 / 不同靶区节点）：按 `material_slug` 归并后再裁定。
- 别名的权威表是 `tables/alias_map.csv`；表里没有该节点时退回包内自带的 `aliases`
  （表的「未列出即清空」只在生成侧生效，审计侧要看现状）。
- `material_bindings.csv`（人工裁定表）只作**注记**：裁定目标与现绑不一致时，证据里写明
  「裁定未生效，待 build/promote」；不据此过滤候选，也不据此改绑。

## 用法（仓库根下）

```
PYTHONPATH=tools python -m kb_build.audit_content_bindings                  # 打印候选统计
PYTHONPATH=tools python -m kb_build.audit_content_bindings --write          # 落候选表
PYTHONPATH=tools python -m kb_build.audit_content_bindings --slices         # 落裁定切片
PYTHONPATH=tools python -m kb_build.audit_content_bindings --chapter X --nodes a,b   # 换靶区
```

靶区缺省 = 两个最弱章（整章）＋ 切片计划点名的节点（见 `SLICE_PLAN`；锚点走 C4，
它们的富节点不进 C1/C2 靶区，要看用 `--nodes` 点名）。

产物：候选表 `tools/kb_build/tables/content_audit_2026-09-25.csv`（八列，`verdict` 空），
切片 `build/audit-slices/slice-NN.json`（相对 `work_dir()` 的同级 `audit-slices/`）。

**本工具不写成品目录**：只读包与表，写只写新表 / 新切片（路径全部由 `pack_io.work_dir()`
与 `tables.TABLES_DIR` 派生）。
"""

from __future__ import annotations

import argparse
import csv
import math
import random
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

from kb_build import pack_io, tables

# ---------------------------------------------------------------------------
# 常量与靶区（Stage-4 本轮）
# ---------------------------------------------------------------------------

TABLE_NAME = "content_audit_2026-09-25.csv"
ANCHOR_TABLE = "content_audit_anchors.csv"
SLICE_DIRNAME = "audit-slices"
MISSES_NAME = "golden-fused-misses.txt"          # 相对 work_dir().parent（build/）
COLUMNS = ("subject", "slug", "material_slug", "current_node_slug", "suggested_node_slug",
           "verdict", "evidence", "slice")
CONTENT_HEAD = 800
MATCH_MIN = 0.50        # C3 的 IDF 加权命中下限
RARE_DF = 3             # C3 的稀有词门槛：DF ≤ 3 才算「讲了节点名里最独特的那个词」
SAMPLE_SEED = 20260925
SAMPLE_PER_SUBJECT = 10

CHAPTER_PHYSICS = "物理必修第一册·第三章·相互作用"
CHAPTER_CHEMISTRY = "化学必修第一册·第三章·铁与金属材料"
CHAPTER_MATH_FUNC = "数学必修第一册·第三章·函数的概念与性质"
CHAPTER_MATH_SERIES = "数学选择性必修第二册·第四章·数列"

# 本轮靶区（Stage-4）：WP0 真机金标跑分（build/wp0-golden-device5-logcat.txt）里两个最弱章的
# 5+5 例 MISS 涉及节点 + 登记锚点 + 随机分层抽样。金标集本身冻结：这里只引用它的
# expectedSlug / chapter 字面，不读、不改、不重排金标文件。
SLICE_PLAN = (
    dict(slice="slice-01", scope="physics-interaction-miss", kind="nodes", subject="PHYSICS",
         chapter=CHAPTER_PHYSICS,
         label="物理·相互作用 MISS 前 3 例（轻绳弹簧对比 / 滑动摩擦力 / 活结与死结模型）",
         nodes=("轻绳、轻弹簧与轻质弹性绳的对比", "滑动摩擦力", "活结与死结模型")),
    dict(slice="slice-02", scope="physics-interaction-miss", kind="nodes", subject="PHYSICS",
         chapter=CHAPTER_PHYSICS,
         label="物理·相互作用 MISS 后 2 例（力的分解 / 动态平衡问题）",
         nodes=("力的分解", "动态平衡问题")),
    dict(slice="slice-03", scope="chemistry-iron-miss", kind="nodes", subject="CHEMISTRY",
         chapter=CHAPTER_CHEMISTRY,
         label="化学·铁与金属材料 MISS 前 3 例（铁三角 / 氢氧化铝制备 / 电解原理易错点）",
         nodes=("铁三角-中的转化关系", "氢氧化铝的制备方法", "电解原理易错点")),
    dict(slice="slice-04", scope="chemistry-iron-miss", kind="nodes", subject="CHEMISTRY",
         chapter=CHAPTER_CHEMISTRY,
         label="化学·铁与金属材料 MISS 后 2 例（镁的化学性质 / 铁的化学性质）",
         nodes=("镁的化学性质", "铁的化学性质")),
    dict(slice="slice-05", scope="i04-anchors", kind="anchors", subject="", chapter="",
         label="登记册 I-04 的 6 条已核实错绑锚点（在当前包上逐条复核）", nodes=()),
    dict(slice="slice-06", scope="random-stratified-40", kind="sample", subject="", chapter="",
         label="随机分层抽样 40 条材料（四科各 10；首份抽样数，与靶子样本分开统计）", nodes=()),
    dict(slice="slice-07", scope="math-weak-chapter-a", kind="nodes", subject="MATH",
         chapter=CHAPTER_MATH_FUNC,
         label="数学次弱章 A·函数的概念与性质（4 例 MISS 涉及节点）",
         nodes=("对勾函数",
                "利用奇偶性求值-将待求函数值或不等式利用奇偶性转化为已知区间上的函数值求解",
                "函数图象的翻折变换",
                "方程组法-主要解决已知与-的方程-求解析式")),
    dict(slice="slice-08", scope="math-weak-chapter-b", kind="nodes", subject="MATH",
         chapter=CHAPTER_MATH_SERIES,
         label="数学次弱章 B·数列（4 例 MISS 涉及节点）",
         nodes=("平方和与立方和公式", "数列的单调性与最值问题", "斐波那契数列", "等比数列的常用性质")),
)

# C1/C2 覆盖的章（整章审：邻节点里可能藏着本该属于靶节点的材料）
SCOPE_CHAPTERS = (CHAPTER_PHYSICS, CHAPTER_CHEMISTRY)

# ---------------------------------------------------------------------------
# 词面
# ---------------------------------------------------------------------------

_ASCII = re.compile(r"[a-z][a-z0-9]{1,}")
_NUM_PREFIX = re.compile(r"^(?:[0-9]{1,2}[.、)）]+|[①②③④⑤⑥⑦⑧⑨⑩]+)\s*")
_PREDICATE = "是为指"
# 判断词长在这些字后面时是复合词的一部分（视为/认为/作为/称为…），不是判断词
_PREDICATE_TAIL = "视认以作称行变分因更最较为成化尤极颇"


def terms(text: str) -> set[str]:
    """词面特征：CJK 二元组 + ASCII 词元（小写）。不做分词、不引模型。"""
    lowered = (text or "").lower()
    cjk = re.sub(r"[^\u4e00-\u9fff]+", "", lowered)
    out = {cjk[i:i + 2] for i in range(len(cjk) - 1)}
    out |= set(_ASCII.findall(lowered))
    return out


def cjk_len(text: str) -> int:
    return sum(1 for ch in text or "" if "\u4e00" <= ch <= "\u9fff")


def _is_rare(term: str, df: int) -> bool:
    """稀有词：DF ≤ RARE_DF，且**两字 ASCII 词元不算**。

    为什么排除 `fe`/`oh`/`pd`：在节点名语料里它们 DF 极小（3,572 个节点名里没几个含 `fe`），
    于是 `Fe(OH)3胶体制备` 会「强匹配」到 `Fe(OH)2的制备`（首版实测的假阳性）——
    这类两字片段是通用化学片段，不是「节点名里最独特的那个词」。三字以上（`trna`）仍算。
    """
    if df > RARE_DF:
        return False
    return not (term.isascii() and len(term) < 3)


def first_subject(text: str) -> str:
    """材料正文首句的**主语短语**：第一个非空行里、判断词（是/为/指）之前的那一段。

    只认判断句：行首小标题（`操作与现象：…`）、公式行、编号清单行都不是主语，
    硬当主语会产出成批噪声（首版实测：`蒸馏水煮沸排出溶解O2` 这种行把正确绑定也报了）。
    判断词若长在复合词里（`视为`/`认为`/`以为`/`作为`…）不算判断词——否则
    `物体缓慢移动时每一时刻均可视为平衡状态` 会切出 `…均可视` 这种假主语。
    """
    for raw in (text or "").splitlines():
        line = _NUM_PREFIX.sub("", raw.strip())
        if not line:
            continue
        for position, char in enumerate(line):
            if char not in _PREDICATE:
                continue
            if position and line[position - 1] in _PREDICATE_TAIL:
                continue
            subject = re.sub(r"[^\u4e00-\u9fffA-Za-z0-9]", "", line[:position])
            return subject if 2 <= cjk_len(subject) <= 30 else ""
        return ""
    return ""


# ---------------------------------------------------------------------------
# 只读快照
# ---------------------------------------------------------------------------


class Facts:
    """包 + 权威表的只读快照，加词面 / IDF 索引（审计只读它）。"""

    def __init__(self, pack, materials, aliases, chapter_map, material_bindings):
        self.pack = pack
        self.materials = materials                       # [(sidecar 路径, 材料 dict)]
        self.alias_map = aliases                         # (subject, slug) -> [alias]
        self.chapter_map = chapter_map                   # (subject, slug) -> (volume, chapter, theme)
        self.material_bindings = material_bindings       # material slug -> point slug（人工裁定表）
        self.point: dict[tuple[str, str], dict] = {}
        self.topic: dict[tuple[str, str], dict] = {}
        for subject, topic, point in pack_io.iter_points(pack):
            key = (subject, point["slug"])
            self.point[key] = point
            self.topic[key] = topic
        self.name_terms = {key: terms(point["name"]) for key, point in self.point.items()}
        self.node_terms = {key: terms(point["name"] + " " + " ".join(self.aliases_of(key)))
                           for key, point in self.point.items()}
        self.df = Counter(term for ts in self.name_terms.values() for term in ts)
        self.node_count = len(self.point)
        self.by_node: dict[tuple[str, str], list[dict]] = defaultdict(list)
        self.node_of: dict[str, tuple[str, str]] = {}
        for _path, material in materials:
            for binding in material.get("bindings") or []:
                key = (material["subject"], binding["knowledgeNodeId"].rsplit(":", 1)[-1])
                self.by_node[key].append(material)
                self.node_of.setdefault(material["slug"], key)

    def aliases_of(self, key: tuple[str, str]) -> list[str]:
        """权威表优先；表里没这个节点就退回包内自带的别名（审计看现状）。"""
        listed = self.alias_map.get(key)
        if listed is not None:
            return [a for a in listed if a]
        return [a for a in (self.point[key].get("aliases") or []) if a]

    def material(self, slug: str) -> dict | None:
        for _path, material in self.materials:
            if material["slug"] == slug:
                return material
        return None

    def idf(self, term: str) -> float:
        return math.log((self.node_count + 1) / (self.df[term] + 1)) + 1.0


def load_facts() -> Facts:
    return Facts(
        pack_io.load_json(pack_io.pack_path()),
        pack_io.load_materials(),
        tables.load_alias_map(),
        tables.load_chapter_map(),
        tables.load_material_bindings(),
    )


def load_anchors() -> list[dict]:
    """登记锚点表（`tables/content_audit_anchors.csv`）；缺表 = 无锚点，不报错。"""
    path = tables.TABLES_DIR / ANCHOR_TABLE
    if not path.exists():
        return []
    with path.open(encoding="utf-8", newline="") as handle:
        return [row for row in csv.DictReader(handle)
                if (row.get("material_slug") or "").strip()]


# ---------------------------------------------------------------------------
# 靶区
# ---------------------------------------------------------------------------


def chapter_nodes(facts: Facts, chapter: str) -> set[tuple[str, str]]:
    """整章节点：topic slug 全等 `chapter` 的那些 topic 下的全部知识点。"""
    return {key for key, topic in facts.topic.items() if topic["slug"] == chapter}


def default_scope(facts: Facts) -> set[tuple[str, str]]:
    """本轮靶区 = 两个最弱章（整章）＋ 切片计划里点名的节点。

    登记锚点**不进** C1/C2 靶区：它们的当前节点（细胞壁/激素/翻译…）是生物必修一/二的
    富节点，拉进来会多出几十行同科噪声、稀释两章的 P0 目标；锚点本身走 C4 逐条复核，
    要看它们的邻域用 `--nodes` 点名即可。
    """
    scope: set[tuple[str, str]] = set()
    for chapter in SCOPE_CHAPTERS:
        scope |= chapter_nodes(facts, chapter)
    for spec in SLICE_PLAN:
        for slug in spec["nodes"]:
            key = (spec["subject"], slug)
            if key in facts.point:
                scope.add(key)
    return scope


def resolve_scope(facts: Facts, chapters, nodes, subject) -> set[tuple[str, str]]:
    """`--chapter/--nodes` 给出的显式靶区；一个都没给时用本轮默认靶区。"""
    scope: set[tuple[str, str]] = set()
    for chapter in chapters or ():
        scope |= chapter_nodes(facts, chapter)
    for slug in nodes or ():
        keys = [k for k in facts.point if k[1] == slug and (not subject or k[0] == subject)]
        scope |= set(keys)
    return scope or default_scope(facts)


# ---------------------------------------------------------------------------
# 判据
# ---------------------------------------------------------------------------


def _row(subject: str, node_slug: str, material: dict, current: str, criterion: str,
         evidence: str, suggested: str | None, slice_id: str | None) -> dict:
    return {
        "subject": subject,
        "slug": node_slug,
        "material_slug": material["slug"],
        "current_node_slug": current,
        "suggested_node_slug": suggested or "",
        "verdict": "",
        "evidence": "%s｜%s" % (criterion, evidence),
        "slice": slice_id or "",
    }


def _slice_of(facts: Facts, key: tuple[str, str]) -> str:
    for spec in SLICE_PLAN:
        if spec["kind"] == "nodes" and spec["subject"] == key[0] and key[1] in spec["nodes"]:
            return spec["slice"]
    return ""


def _annotation(facts: Facts, material: dict) -> str:
    """人工裁定表里的既成事实，附在证据尾部（不据此过滤，也不据此改绑）。"""
    decided = facts.material_bindings.get(material["slug"])
    if decided is None:
        return ""
    current = facts.node_of.get(material["slug"], (material["subject"], ""))[1]
    if decided == current:
        return "｜注意：权威表 material_bindings.csv 已裁定目标=「%s」，与当前绑定一致（人工已定）" % decided
    return "｜注意：权威表 material_bindings.csv 已裁定目标=「%s」，当前包仍是「%s」（裁定未生效，待 build/promote）" % (
        decided, current)


def own_detached_rows(facts: Facts, scope) -> list[dict]:
    """C1/C1'/C2：材料绑在靶区节点上，却与这个节点「名字（+别名）」零词面交集。

    每个材料至多出一行（按 C1 → C1' → C2 的强度取第一条命中的）。
    C1' 是**弱**判据：命中只说明标题与节点**名**一个字对不上、只在别名里挂上——
    而别名表正是从绑定材料的标题重建的（J-05 别名污染），所以这条是自证，不是旁证。
    """
    rows: list[dict] = []
    for key in sorted(scope):
        point = facts.point.get(key)
        if point is None:
            continue
        name_terms = facts.name_terms[key]
        node_terms = facts.node_terms[key]
        alias_count = len(facts.aliases_of(key))
        for material in facts.by_node.get(key, []):
            title = material.get("title") or ""
            title_terms = terms(title)
            source = material.get("sourceLocator") or "?"
            annotation = _annotation(facts, material)
            if title_terms and not (title_terms & node_terms):
                rows.append(_row(
                    key[0], key[1], material, key[1], "own-title-detached",
                    "标题《%s》与节点「%s」的名字+别名（%d 条）零共同词面（标题词元 %d 个全落空）"
                    "｜源：%s%s" % (title, point["name"], alias_count, len(title_terms), source,
                                    annotation),
                    None, _slice_of(facts, key)))
                continue
            if title_terms and not (title_terms & name_terms):
                rows.append(_row(
                    key[0], key[1], material, key[1], "own-title-alias-only",
                    "标题《%s》与节点**名**「%s」零共同词面，只在别名里挂上（%d 条别名；"
                    "别名表由绑定材料标题重建 ⇒ 自证不算旁证）｜源：%s%s"
                    % (title, point["name"], alias_count, source, annotation),
                    None, _slice_of(facts, key)))
                continue
            subject_text = first_subject(material.get("contentMarkdown")
                                         or material.get("summaryMarkdown"))
            if cjk_len(subject_text) >= 2 and not (terms(subject_text) & node_terms):
                rows.append(_row(
                    key[0], key[1], material, key[1], "own-subject-detached",
                    "首句主语「%s」与节点「%s」的名字+别名（%d 条）零共同词面"
                    "｜标题《%s》｜源：%s%s" % (subject_text, point["name"], alias_count, title,
                                               source, annotation),
                    None, _slice_of(facts, key)))
    return rows


def _match(facts: Facts, key: tuple[str, str], text_terms: set[str]) -> tuple[float, tuple[str, ...]]:
    """IDF 加权命中：返回 (加权分, 命中的稀有词元)。

    所有遍历都走 `sorted(...)`：集合的迭代序随 PYTHONHASHSEED 变，浮点求和的次序一变，
    末位就不同——两次运行的产物会不一样（本模块的「可复算」靠这一行兜住）。
    稀有词并列时按 (DF, 词元) 排，别让并列顺序跟着哈希走。
    """
    node_terms = facts.name_terms[key]
    hit = node_terms & text_terms
    if not hit:
        return 0.0, ()
    total = sum(facts.idf(term) for term in sorted(node_terms))
    if total <= 0:
        return 0.0, ()
    score = sum(facts.idf(term) for term in sorted(hit)) / total
    rare = tuple(sorted((term for term in hit if _is_rare(term, facts.df[term])),
                        key=lambda term: (facts.df[term], term)))
    return score, rare


def foreign_title_rows(facts: Facts, scope) -> list[dict]:
    """C3：材料绑在靶区外，标题却把靶区某节点的名字讲全了（且比它当前节点更像）。"""
    rows: list[dict] = []
    targets = sorted(scope)
    for _path, material in facts.materials:
        subject = material["subject"]
        current = facts.node_of.get(material["slug"])
        if current in scope:
            continue
        title_terms = terms(material.get("title"))
        if not title_terms:
            continue
        own_score, _ = _match(facts, current, title_terms) if current in facts.point else (0.0, ())
        best: tuple[float, tuple[str, ...], tuple[str, str]] | None = None
        for key in targets:
            if key[0] != subject:
                continue
            score, rare = _match(facts, key, title_terms)
            if score < MATCH_MIN or not rare or score <= own_score:
                continue
            if best is None or (score, rare) > (best[0], best[1]):
                best = (score, rare, key)
        if best is None:
            continue
        score, rare, key = best
        rows.append(_row(
            subject, key[1], material, current[1] if current else "", "foreign-title-match",
            "标题《%s》与靶区节点「%s」IDF 加权命中 %.2f（稀有词：%s）＞ 与当前节点「%s」的 %.2f"
            "｜源：%s%s" % (material.get("title") or "", facts.point[key]["name"], score,
                            "、".join(rare), facts.point[current]["name"] if current else "（无绑定）",
                            own_score, material.get("sourceLocator") or "?",
                            _annotation(facts, material)),
            key[1], _slice_of(facts, key)))
    return rows


def anchor_rows(facts: Facts) -> list[dict]:
    """C4：登记锚点逐条复核（登记时的绑定 vs 当前包的绑定）。"""
    slice_id = next((spec["slice"] for spec in SLICE_PLAN if spec["kind"] == "anchors"), "")
    rows: list[dict] = []
    for anchor in load_anchors():
        material_slug = anchor["material_slug"].strip()
        registered = (anchor.get("registered_node_slug") or "").strip()
        material = facts.material(material_slug)
        if material is None:
            rows.append({
                "subject": (anchor.get("subject") or "").strip(),
                "slug": registered, "material_slug": material_slug,
                "current_node_slug": "", "suggested_node_slug": "",
                "verdict": "",
                "evidence": "registered-anchor｜材料不在当前包内（登记锚点失效？）｜%s" % (
                    anchor.get("note") or ""),
                "slice": slice_id,
            })
            continue
        current = facts.node_of.get(material_slug)
        current_slug = current[1] if current else ""
        if current_slug == registered:
            state = "**仍是登记时的绑定**（未修复）"
        elif not current_slug:
            state = "当前包内该材料无绑定"
        else:
            state = "已改绑为「%s」" % current_slug
        hit = terms(material.get("title")) & facts.node_terms.get(current, set()) if current else set()
        name_hit = terms(material.get("title")) & facts.name_terms.get(current, set()) if current else set()
        rows.append(_row(
            material["subject"], current_slug or registered, material, current_slug,
            "registered-anchor",
            "登记错绑（%s）时绑「%s」；现绑「%s」⇒ %s｜标题《%s》∩现节点名 %d 个词元、"
            "∩名字+别名 %d 个｜源：%s"
            % (anchor.get("register_ref") or "?", registered, current_slug or "（无）", state,
               material.get("title") or "", len(name_hit), len(hit),
               material.get("sourceLocator") or "?"),
            None, slice_id))
    return rows


def verdict_rows(facts: Facts, scope) -> list[dict]:
    """全部候选行（不分判据），按 (切片, 科, 靶区节点, 材料) 定序。"""
    rows = anchor_rows(facts) + own_detached_rows(facts, scope) + foreign_title_rows(facts, scope)
    rows.sort(key=lambda row: (row["slice"], row["subject"], row["slug"], row["material_slug"],
                               row["evidence"]))
    return rows


# ---------------------------------------------------------------------------
# 切片
# ---------------------------------------------------------------------------


def _rank_index() -> dict[str, dict]:
    """金标题 → 该题 expectedSlug 在生产词面腿里的名次（复用 stage3_expectation.lexical_rows）。

    只读产物（词面腿 TSV + 金标集），不写、不改；任一产物不在位就返回空（切片照出，名次留空）。
    """
    out: dict[str, dict] = {}
    try:
        sys.path.insert(0, str(pack_io.REPO / "tools" / "dense_build"))
        import dense_asset as D                                   # noqa: PLC0415
        import stage3_expectation as S                            # noqa: PLC0415
        root = pack_io.REPO
        leg_path = root.joinpath(*D.LEXICAL_LEG_RELATIVE.split("/"))
        if not leg_path.is_file():
            return {}
        leg = S.lexical_rows(leg_path)
        for index, case in enumerate(D.goldens(root)):
            node_id = case["expectedSlug"]
            rows = leg.get(index) or []
            rank = 0
            for position, (row_id, _count) in enumerate(rows, 1):
                if row_id.endswith(":atomic:" + node_id):
                    rank = position
                    break
            out[node_id] = dict(lexicalRank=rank, lexCandidates=len(rows), query=case["query"],
                                chapter=case["chapter"])
    except Exception as error:                                    # noqa: BLE001
        out["__note__"] = {"error": "%s: %s" % (type(error).__name__, error)}
    return out


def _miss_queries() -> dict[str, list[dict]]:
    """`build/golden-fused-misses.txt`（WP0 真机跑分产物）→ expectedSlug -> [MISS 行]。"""
    path = pack_io.work_dir().parent / MISSES_NAME
    out: dict[str, list[dict]] = {}
    if not path.is_file():
        return out
    pattern = re.compile(r"^\s*MISS \[(?P<subject>[A-Z]+)\] (?P<query>.*?) \| expectedSlug=(?P<slug>\S+)"
                         r" \| chapter=(?P<chapter>.*?) \| rank=(?P<rank>\S+)\s*$")
    for line in path.read_text(encoding="utf-8").splitlines():
        match = pattern.match(line)
        if not match:
            continue
        out.setdefault(match.group("slug"), []).append(dict(
            subject=match.group("subject"), query=match.group("query"),
            chapter=match.group("chapter"), fusedRank=match.group("rank")))
    return out


def _material_record(facts: Facts, material: dict) -> dict:
    current = facts.node_of.get(material["slug"])
    return {
        "slug": material["slug"],
        "subject": material["subject"],
        "title": material.get("title") or "",
        "type": material.get("type") or "",
        "current_node_slug": current[1] if current else "",
        "current_node_name": facts.point[current]["name"] if current in facts.point else "",
        "sourceLocator": material.get("sourceLocator") or "",
        "summaryMarkdown": material.get("summaryMarkdown") or "",
        "contentHead800": (material.get("contentMarkdown") or "")[:CONTENT_HEAD],
        "contentLength": len(material.get("contentMarkdown") or ""),
    }


def _node_record(facts: Facts, key: tuple[str, str]) -> dict:
    point = facts.point[key]
    topic = facts.topic.get(key) or {}
    chapter = facts.chapter_map.get(key)
    return {
        "subject": key[0],
        "slug": key[1],
        "name": point["name"],
        "kind": point.get("kind") or "",
        "boundary": point.get("boundary") or "",
        "aliases": facts.aliases_of(key),
        "topicSlug": topic.get("slug") or "",
        "topicName": topic.get("name") or "",
        "chapterMapVolume": chapter[0] if chapter else "",
        "chapterMapChapter": chapter[1] if chapter else "",
        "chapterMapTheme": chapter[2] if chapter else "",
        "materialCount": len(facts.by_node.get(key, [])),
    }


def _sample_materials(facts: Facts) -> list[dict]:
    """四科各 10 条的均匀随机抽样（固定种子；总体 = 包内有绑定的材料）。"""
    by_subject: dict[str, list[str]] = defaultdict(list)
    for material in facts.materials:
        if material[1]["slug"] in facts.node_of:
            by_subject[material[1]["subject"]].append(material[1]["slug"])
    rng = random.Random(SAMPLE_SEED)
    out: list[dict] = []
    for subject in sorted(by_subject):
        pool = sorted(by_subject[subject])
        take = min(SAMPLE_PER_SUBJECT, len(pool))
        for slug in rng.sample(pool, take):
            out.append(facts.material(slug))
    return [m for m in out if m]


def _target_material_slugs(facts: Facts) -> set[str]:
    """靶区样本 = 靶区节点绑定的材料 + 登记锚点材料（抽样片要报它与靶子样本的重叠）。"""
    slugs = {material["slug"] for key in default_scope(facts)
             for material in facts.by_node.get(key, [])}
    for anchor in load_anchors():
        slugs.add(anchor["material_slug"].strip())
    return slugs


def build_slice(facts: Facts, spec: dict, ranks: dict, misses: dict, rows=()) -> dict:
    keys: list[tuple[str, str]] = []
    materials: list[dict] = []
    sample_note = ""
    if spec["kind"] == "nodes":
        keys = [(spec["subject"], slug) for slug in spec["nodes"] if (spec["subject"], slug) in facts.point]
        for key in keys:
            materials.extend(facts.by_node.get(key, []))
    elif spec["kind"] == "anchors":
        for anchor in load_anchors():
            material = facts.material(anchor["material_slug"].strip())
            if material:
                materials.append(material)
                key = facts.node_of.get(material["slug"])
                if key:
                    keys.append(key)
        keys = sorted(set(keys))
        materials.extend(m for key in keys for m in facts.by_node.get(key, []))
    elif spec["kind"] == "sample":
        materials = _sample_materials(facts)
        keys = sorted({facts.node_of[m["slug"]] for m in materials if m["slug"] in facts.node_of})
        overlap = sorted(m["slug"] for m in materials
                         if m["slug"] in _target_material_slugs(facts))
        sample_note = ("抽样口径：按科分层的均匀随机（random.Random(%d)，每科 %d 条，"
                       "总体=包内有绑定的材料）；本片是**首份抽样数**，与靶子样本分开统计"
                       "（与靶区/锚点材料重叠 %d 条%s）。"
                       % (SAMPLE_SEED, SAMPLE_PER_SUBJECT, len(overlap),
                          "：" + "、".join(overlap[:5]) if overlap else ""))
    seen: set[str] = set()
    unique_materials = []
    for material in materials:
        if material["slug"] in seen:
            continue
        seen.add(material["slug"])
        unique_materials.append(_material_record(facts, material))
    nodes = []
    for key in keys:
        record = _node_record(facts, key)
        rank = ranks.get(key[1]) or {}
        if rank:
            record["lexicalLeg"] = rank
        nodes.append(record)
    queries = []
    for key in keys:
        queries.extend(misses.get(key[1], []))
    return {
        "slice": spec["slice"],
        "scope": spec["scope"],
        "label": spec["label"],
        "kind": spec["kind"],
        "index": int(spec["slice"].split("-")[-1]),
        "chapter": spec["chapter"] or None,
        "note": sample_note or spec["label"],
        "nodes": nodes,
        "materials": unique_materials,
        "candidates": [dict(row) for row in rows],
        "missQueries": queries,
        "honesty": {
            "candidatesNotVerdicts": "本片只给内容与现绑关系，不下裁定；裁定在 WP2。",
            "nodeMaterials": ("nodes[].materialCount = 该节点在当前包里的全部绑定材料数；"
                              "materials[] = 本片上桌的材料（nodes 片=全部绑定材料，抽样片=被抽中的那些）。"),
            "candidates": ("candidates[] = 判据打在**本片节点或本片材料**上的候选行（与候选表同列）；"
                           "表里 slice 列为空的行是章级候选（不在任何切片里），要一起扫请按章读表。"),
            "goldenGuardrail": ("missQueries 仅供诊断定位（金标真机跑分里该节点的 MISS 题面与名次）；"
                                "改绑必须由材料正文/课标/教材本身支撑，不得以『金标期望这个节点』为由。"),
            "lexicalLegNote": ("nodes[].lexicalLeg.lexicalRank = 金标题在生产词面腿（v1 计数排序）"
                               "里的名次（0=该题词面腿无此节点）；来自 build/production-lexical-leg.tsv × "
                               "金标集，复用 stage3_expectation.lexical_rows()，不重写特征抽取。"),
        },
    }


def write_slices(facts: Facts, slice_dir: Path | None = None, rows=None) -> list[Path]:
    ranks = _rank_index()
    misses = _miss_queries()
    rows = verdict_rows(facts, default_scope(facts)) if rows is None else rows
    out_dir = slice_dir or (pack_io.work_dir().parent / SLICE_DIRNAME)
    out_dir.mkdir(parents=True, exist_ok=True)
    written = []
    for spec in SLICE_PLAN:
        if spec["kind"] == "sample":
            # 抽样片的候选 = 判据打在**被抽中的材料**上的行（节点不在靶区里，按材料归属）
            sampled = {m["slug"] for m in _sample_materials(facts)}
            own = [row for row in rows if row["material_slug"] in sampled]
        else:
            own = [row for row in rows if row["slice"] == spec["slice"]]
        payload = build_slice(facts, spec, ranks, misses, own)
        payload["generatedFrom"] = {
            "pack": pack_io.PACK_NAME,
            "workDir": str(pack_io.work_dir().relative_to(pack_io.REPO)).replace("\\", "/"),
            "misses": "%s（%s）" % (MISSES_NAME, "在位" if misses else "不在位——missQueries 为空"),
            "lexicalRanks": ("已算（%d 题）" % len([k for k in ranks if not k.startswith("__")])
                             if ranks and "__note__" not in ranks
                             else "未算：%s" % (ranks.get("__note__", {}).get("error", "产物不在位"))),
        }
        path = out_dir / ("%s.json" % spec["slice"])
        pack_io.dump_json(payload, path)
        written.append(path)
    return written


# ---------------------------------------------------------------------------
# 落表 / 入口
# ---------------------------------------------------------------------------


def write_table(rows: list[dict], path: Path | None = None) -> Path:
    target = path or (tables.TABLES_DIR / TABLE_NAME)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(COLUMNS))
        writer.writeheader()
        for row in rows:
            writer.writerow({column: row.get(column, "") for column in COLUMNS})
    return target


def summarise(rows: list[dict]) -> Counter:
    stats = Counter()
    for row in rows:
        stats["total"] += 1
        stats["criterion:" + row["evidence"].split("｜")[0]] += 1
        stats["subject:" + row["subject"]] += 1
        stats["slice:" + (row["slice"] or "（不在切片）")] += 1
    return stats


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--chapter", action="append", default=[], help="靶区章（topic slug，可重复）")
    parser.add_argument("--nodes", action="append", default=[], help="靶区节点 slug（可逗号分隔）")
    parser.add_argument("--subject", default="", help="配合 --nodes 限定科目")
    parser.add_argument("--write", action="store_true", help="落候选表")
    parser.add_argument("--slices", action="store_true", help="落裁定切片")
    parser.add_argument("--show", type=int, default=12, help="打印前 N 行候选")
    args = parser.parse_args(argv)

    facts = load_facts()
    slugs = [slug for item in args.nodes for slug in item.split(",") if slug]
    scope = resolve_scope(facts, args.chapter, slugs, args.subject)
    rows = verdict_rows(facts, scope)
    stats = summarise(rows)
    print("靶区节点 %d 个（章：%s）" % (len(scope), "、".join(args.chapter) or "（本轮默认）"))
    print("候选行 %d：" % stats["total"])
    for name, count in sorted(stats.items()):
        if name.startswith("criterion:"):
            print("  %-34s %d" % (name.split(":", 1)[1], count))
    print("按科：", {k.split(":", 1)[1]: v for k, v in sorted(stats.items()) if k.startswith("subject:")})
    print("按片：", {k.split(":", 1)[1]: v for k, v in sorted(stats.items()) if k.startswith("slice:")})
    for row in rows[:args.show]:
        print("  [%s] %s | %s -> %s | %s" % (row["slice"] or "  -  ", row["subject"][:4],
                                             row["material_slug"][:34], row["slug"][:22],
                                             row["evidence"][:110]))
    if args.write:
        print("→ 候选表 %s（%d 行，verdict 全空待 WP2）" % (write_table(rows), len(rows)))
    if args.slices:
        paths = write_slices(facts)
        print("→ 切片 %d 片：" % len(paths))
        for path in paths:
            print("   %s" % path.relative_to(pack_io.REPO))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
