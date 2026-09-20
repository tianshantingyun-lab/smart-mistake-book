"""扫"近重复节点"候选：名字规范化后相同/包含的同科节点。

用途：错绑审计反复暴露同一类问题——**同一个知识点在库里有两个节点**（只差用字/标点/后缀），
材料在两者之间来回搬。本扫描把它们成组列出，供下一轮逐组裁定合并。

判据（机械，只做分组不做结论）：
- 规范化：去掉标点/括号内容/全角空格、去掉末尾的「问题/方法/计算/的应用/的思路」等空后缀、
  统一「与/和/、」为「和」；
- 同科内规范化名相同 → 记为 `same`；一方是另一方的前缀（≥4 字）→ 记为 `prefix`；
- 只列同 topic 或跨 topic 都标出来（跨 topic 的往往是历史归位遗留）。
"""
import re
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, "tools")
from kb_build import pack_io  # noqa: E402

DROP_PAREN = re.compile(r"[（(][^（）()]*[）)]")
DROP_PUNCT = re.compile(r"[·、，,．.：:；;・\-—_/\\\[\]【】「」“”\"'’‘!！?？]")
# 只剥**空后缀**（不含信息量的尾巴）。刻意不含「方法/计算/比较/应用/规律」这类——
# 它们是知识点之间真正的区别（「化学反应速率的计算方法」≠「化学反应速率的比较」），
# 剥掉会把两个合法节点并成一组（第一版扫描实测踩过这个坑：32 组里混进一批合法细分）。
SUFFIXES = ("问题", "的方法", "的思路")
UNIFY = {"与": "和", "及": "和"}


def norm(name: str) -> str:
    s = DROP_PAREN.sub("", name)
    s = DROP_PUNCT.sub("", s)
    for k, v in UNIFY.items():
        s = s.replace(k, v)
    for suf in SUFFIXES:
        if len(s) > len(suf) + 3 and s.endswith(suf):
            s = s[: -len(suf)]
    return s


pack = pack_io.load_json(pack_io.pack_path())
node = {}
for s in pack["subjects"]:
    for t in s["topics"]:
        for kp in (t.get("knowledgePoints") or []):
            node[(s["subject"], kp["slug"])] = (kp["name"], t["slug"])

counts = defaultdict(int)
for sp in pack_io.sidecar_paths():
    for m in pack_io.load_json(sp)["materials"]:
        for b in m.get("bindings") or []:
            counts[b["knowledgeNodeId"].split(":")[-1]] += 1
            break

groups = defaultdict(list)
for (subj, slug), (name, topic) in node.items():
    groups[(subj, norm(name))].append((slug, name, topic, counts.get(slug, 0)))

same = {k: v for k, v in groups.items() if len(v) > 1}
prefix_pairs = []
keys = list(groups)
for (subj, key) in keys:
    for (other_subj, other_key) in keys:
        if other_subj != subj or other_key == key:
            continue
        if len(key) >= 5 and other_key.startswith(key) and len(other_key) > len(key):
            short, long = groups[(subj, key)], groups[(other_subj, other_key)]
            # 只收"同一主题内 + 短的那侧材料 ≤2 条"的形态：那才是遗留空壳，不是合法细分
            if short[0][2].split("·")[-1] == long[0][2].split("·")[-1] and min(
                    c for _s, _n, _t, c in short) <= 2:
                prefix_pairs.append((subj, key, other_key, short, long))

print(f"规范化完全同名组：{len(same)} 组")
for (subj, key), items in sorted(same.items(), key=lambda kv: -len(kv[1]))[:12]:
    print(f"  [{subj}] 规范化「{key}」")
    for slug, name, topic, c in items:
        print(f"      {name}（材料 {c}）｜topic={topic}")
print(f"\n前缀包含对：{len(prefix_pairs)} 对（前 8）")
for subj, key, other_key, items, others in prefix_pairs[:8]:
    print(f"  [{subj}] 「{key}」 ⊂ 「{other_key}」")
    for slug, name, topic, c in items + others:
        print(f"      {name}（材料 {c}）")
