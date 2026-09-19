# -*- coding: utf-8 -*-
"""为文本判定轮新出现的知识点名做建点规划：先匹配既有节点，再按章主题归类，最后校验名字。

## 它消灭的失败

代理遇到"候选表里没有的名字"时会写 `NEW:<名字>`。这些名字**不能直接建点**：
1. 有些其实已有节点（大小写/别名/规范化后同名）——直接建会造成同物重复；
2. 名字可能超长或长得像题干（门禁 `bad_name` 会拒），得先挑出来改；
3. 归属必须写清楚（跨章方法落各科综合桶、章内知识落对应章主题），否则又是"挂错地方"。

输出是一张可复核的规划表（含证据与归类理由），落盘后才走 create_points。

用法：
    PYTHONPATH=tools python -m kb_coverage.plan_new_nodes
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

from kb_build import gate, pack_io

REPO = Path(pack_io.REPO).resolve()
MERGED = REPO / "build" / "agent-input" / "merged_text_batch.csv"
NEW_JSON = REPO / "build" / "agent-input" / "text_new_nodes.json"
OUT = REPO / "build" / "agent-input" / "new_nodes_plan.json"

def rules() -> dict[str, list[tuple[str, str]]]:
    """(正则, 目标主题 slug)。按顺序取第一个命中；没有命中走兜底（各科综合桶/数学兜底主题）。"""
    return {
        "MATH": [
            (r"圆锥曲线|椭圆|双曲线|抛物线|焦点|焦半径|切点弦|极点|极线|动点轨迹|隐圆|阿波罗尼斯|"
             r"直线系|弦中点|点差法|硬解|非对称|两圆|切线方程|公共弦|弦长|定值|定点|定线|离心率|"
             r"对称|曼哈顿|托勒密", "数学选择性必修第一册·第二三章·解析几何"),
            (r"数列|递推|放缩|裂项|并项|通项|公共项|插项|去项|斐波那契|倒序", "数学选择性必修第二册·第四章·数列"),
            (r"导数|洛必达|泰勒|同构|切线放缩|主元|端点效应|拐点|隐函数|必要|对数平均|齐次化|三次函数|"
             r"双变量|凹凸|帕德|切线夹|零点|恒成立|整数解", "数学选择性必修第二册·第五章·函数与导数"),
            (r"概率|贝叶斯|马尔可夫|正态|分布列|期望|方差|成对数据|统计|百分位|抽样|独立性检验|回归",
             "数学必修第二册·第九十章（条件概率与正态分布见选择性必修第三册）·概率统计"),
            (r"向量|算两次|等和线|极化|奔驰|定比分点|投影|建系", "数学必修第二册·第六章·平面向量"),
            (r"立体|多面体|外接球|内切球|棱切球|三余弦|正方体|长方体|截面|轨迹|空间|棱柱|棱锥|棱台|旋转体|"
             r"线面|面面|线线|异面|体积|表面积", "数学必修第二册·第八章（空间向量见选择性必修第一册）·立体几何"),
            (r"三角|解三角形|射影定理|角平分线|张角|爪形|正弦|余弦|恒等变换|积化和差|半角|辅助角|扇形|弧长|"
             r"诱导公式|和差|正切", "数学必修第一册·第五章·三角函数"),
            (r"柯西|权方和|不等式|均值|糖水|放缩|比较大小", "数学必修第一册·第二章·不等式"),
            (r"计数|排列|组合|二项式|分组|隔板|捆绑|插空|染色|杨辉|球放盒子", "数学选择性必修第三册·第六章·计数原理"),
            (r"集合|命题|充要|量词|逻辑", "数学必修第一册·第一章·集合与常用逻辑用语"),
            (r"复数|辐角", "数学必修第二册·第七章·复数"),
        ],
        "PHYSICS": [
            (r"匀变速|刹车|自由落体|竖直上抛|追及|相遇|纸带|逐差|速度比", "物理必修第一册·第二章·匀变速直线运动"),
            (r"弹力|摩擦|受力分析|共点力|平衡|合力|力的合成|力的分解|正交分解|绳|杆|弹簧|摩擦角|活结|"
             r"死结|悬挂|摩|力学单位", "物理必修第一册·第三章·相互作用"),
            (r"牛顿|惯性|超重|失重|传送带|板块|连接体|动力学|单位制", "物理必修第一册·第四章·牛顿运动定律"),
            (r"平抛|斜抛|类平抛|圆周|向心|曲线运动|合运动|渡河|离心|抛体", "物理必修第二册·第五六章·抛体运动与圆周运动"),
            (r"万有引力|卫星|双星|天体|宇宙速度|重力加速度|开普勒|黑洞|引力势能|轨道", "物理必修第二册·第七章·万有引力与宇宙航行"),
            (r"动能|机械能|功|功率|能量守恒|变力做功|势能|功能关系", "物理必修第二册·第八章·机械能与能量守恒"),
            (r"动量|冲量|碰撞|爆炸|反冲|人船|弹性碰撞", "物理选择性必修第一册·第一章·动量"),
            (r"简谐|单摆|振动|波|多普勒|受迫|共振", "物理选择性必修第一册·第二三章·机械振动与机械波"),
            (r"折射|全反射|干涉|衍射|偏振|光|透镜|玻璃砖|双缝", "物理选择性必修第一册·第四章·光学"),
            (r"电场|电势|电容|带电粒子|库仑|等势面|示波管|电场强度", "物理必修第三册·第九十章·静电场"),
            (r"电流|电路|电阻|欧姆|电表|电源|电动势|电桥|半偏|伏安|多用电表|滑动变阻器|游标卡尺|"
             r"螺旋测微器|电功率|串联|并联", "物理必修第三册·第十一十二章·恒定电流"),
            (r"磁场|左手定则|洛伦兹|安培|电磁感应|感应电流|楞次|法拉第|涡流|自感|回旋加速器|霍尔|"
             r"电磁流量|磁通量|磁聚焦|切割磁感线|感应电动", "物理必修第三册·第十三章（安培力/洛伦兹力/感应定律见选择性必修第二册）·磁场与电磁感应"),
            (r"交变|变压器|远距离|互感|自耦|传感器|电磁波|电磁振荡", "物理选择性必修第二册·第三四章·交变电流与电磁波"),
            (r"分子|内能|热力学|气体|温度|浸润|毛细|液晶|饱和汽|分子动理论|压强|体积", "物理选择性必修第三册·第一至三章·热学"),
            (r"光电效应|原子|核|衰变|相对论|波粒二象性|能级|光谱|黑体|康普顿|质量亏损|能量子",
             "物理选择性必修第三册·第四五章·近代物理"),
        ],
        "CHEMISTRY": [
            (r"晶胞|晶体|晶格", "化学选择性必修2·物质结构与性质·晶胞与均摊法"),
            (r"杂化|空间构型|vsepr|价层电子对|键角|分子结构|极性|手性", "化学选择性必修2·物质结构与性质·分子的空间结构"),
            (r"电离能|电负性|原子结构|核外电子|电子排布|能层|能级|构造原理|原子光谱|核素",
             "化学选择性必修2·物质结构与性质·原子结构与元素周期表"),
            (r"共价键|化学键|分子间作用力|氢键|配位键", "化学选择性必修2·物质结构与性质·共价键"),
            (r"电化学|电解|原电池|电极|电池|电镀|电解精炼", "化学选择性必修1·第四章·电化学"),
            (r"平衡常数|化学平衡|平衡移动|转化率|速率|活化能|催化剂", "化学选择性必修1·第二章·化学平衡"),
            (r"电离|水解|滴定|ph|沉淀溶解|溶度积|离子浓度|中和", "化学选择性必修1·第三章·水溶液中的离子平衡"),
            (r"反应热|焓变|中和热|盖斯|燃烧热|能源", "化学选择性必修1·第一章·反应热与能量"),
            (r"工艺流程|化工流程|产率|纯度", "化学必修第二册·第六章·化学反应与能量"),
            (r"氧化还原|离子反应|离子方程式|离子共存|化合价", "化学必修第一册·第一章·离子反应与氧化还原"),
            (r"物质的量|摩尔|阿伏加德罗|气体摩尔体积|溶液配制|物质的量浓度",
             "化学必修第一册·第一章·物质分类与转化"),
            (r"钠|过氧化钠|碳酸钠|碳酸氢钠", "化学必修第一册·第二章·钠及其化合物"),
            (r"氯|次氯酸|漂白", "化学必修第一册·第二章·氯及其化合物"),
            (r"铁|亚铁|铝|金属材料|金属冶炼|铜", "化学必修第一册·第三章·铁与金属材料"),
            (r"周期表|元素周期律|碱金属|卤族|元素推断", "化学必修第一册·第四章·物质结构元素周期律"),
            (r"硒|碲|砷|硫|氮|氨|硝酸|硫酸|二氧化硫|二氧化氮|环境|酸雨|酸雾",
             "化学必修第二册·第五章·硫氮及其化合物"),
            (r"钴|锰|铬|镍|铜|锌|银|钛|钒|金属|铁|亚铁|铝", "化学必修第一册·第三章·铁与金属材料"),
            (r"含氧酸|酸性强弱|元素推断|主族元素|稀有气体元素|元素周期|电负性|第一电离能",
             "化学必修第一册·第四章·物质结构元素周期律"),
            (r"过氧化氢|常见元素及其化合物的特性|无机物性质", "化学必修第一册·第四章·物质结构元素周期律"),
            (r"容量瓶|温度计|分液漏斗|干燥管|洗气瓶|洗涤|加热|溶解|蒸发|结晶|过滤|萃取|蒸馏|"
             r"仪器|操作规范|读数|玻璃棒|坩埚|漏斗|冷凝管",
             "CHEMISTRY·综合·实验与方案设计·实验分析"),
            (r"反应与能量|化学能与电|燃料|甲烷的|化石", "化学必修第二册·第六章·化学反应与能量"),
            (r"有机|乙烯|乙醇|乙酸|糖|脂|蛋白|烃|取代|加成|同分异构|官能团|聚合",
             "化学必修第二册·第七章·有机化合物"),
            (r"硅|无机非金属|陶瓷|水泥|玻璃", "化学必修第二册·第五章第三节·无机非金属材料"),
            (r"资源|可持续|环保|绿色化学|废水|大气", "化学必修第二册·第八章·化学与可持续发展"),
            (r"实验|检验|除杂|分离|提纯|装置|气密|试剂",
             "CHEMISTRY·综合·实验与方案设计·实验分析"),
        ],
        "BIOLOGY": [
            (r"细胞器|细胞核|细胞膜|细胞质|细胞结构|核糖体|线粒体|叶绿体|溶酶体",
             "生物学必修1·第三章·细胞结构"),
            (r"蛋白质|核酸|糖|脂质|元素|化合物|水|无机盐", "生物学必修1·第二章·细胞的分子组成"),
            (r"运输|渗透|质壁|胞吞|胞吐|载体|通道蛋白", "生物学必修1·第四章·物质的输入与输出"),
            (r"酶|atp|能量货币", "生物学必修1·第五章·酶与ATP"),
            (r"呼吸|无氧|有氧|细胞呼吸", "生物学必修1·第五章第三节·细胞呼吸"),
            (r"光合|色素|暗反应|光反应|化能合成", "生物学必修1·第五章第四节·光合作用"),
            (r"分裂|癌|衰老|凋亡|分化|干细胞|细胞周期", "生物学必修1·第六章·细胞的生命历程"),
            (r"减数|受精|同源染色体|联会", "生物学必修2·第一章·遗传的细胞基础"),
            (r"遗传|基因|分离定律|自由组合|伴性|系谱|性状|显性|隐性|互换|连锁",
             "生物学必修2·第一章第二节·遗传的基本规律"),
            (r"dna|复制|转录|翻译|中心法则|密码子|基因表达|表观遗传", "生物学必修2·第三四章·遗传的分子基础"),
            (r"变异|进化|突变|重组|染色体|物种|自然选择|协同进化|多样性", "生物学必修2·第五六章·变异与进化"),
            (r"内环境|稳态|神经|反射|激素|体液|血糖|体温|渗透压", "生物学选择性必修1·第一章·内环境与稳态"),
            (r"免疫|抗原|抗体|疫苗|过敏", "生物学选择性必修1·第四章·免疫调节"),
            (r"生长素|植物激素|光周期|分生", "生物学选择性必修1·第五章·植物生命活动的调节"),
            (r"种群|群落|密度|标记重捕|演替", "生物学选择性必修2·第一二章·种群与群落"),
            (r"生态|食物链|能量流动|物质循环|信息传递|生物多样性|保护", "生物学选择性必修2·第三章·生态系统"),
            (r"发酵|微生物|培养|灭菌|培养基|传统发酵", "生物学选择性必修3·生物技术与工程·微生物的培养技术及应用"),
            (r"基因工程|限制酶|载体|重组|转基因|pcr|电泳|分子标记|基因编辑", "生物学选择性必修3·生物技术与工程·基因工程的基本操作程序"),
            (r"细胞工程|植物组织|克隆|胚胎|干细胞工程", "生物学选择性必修3·生物技术与工程·动物细胞工程"),
            (r"实验|探究|观察|显微|试剂|染色|对照", "BIOLOGY·综合·综合·综合"),
        ],
    }


def fallback(subject: str, topics: set[str]) -> str:
    if subject == "MATH":
        return "数学必修第一册·第三章·函数的概念与性质"
    return next(t for t in topics if t.endswith("综合·综合·综合"))


def norm(x: str) -> str:
    return re.sub(r"[\s·,，。、（）()：:\-—/]", "", x).lower()


def canonical(name: str) -> str:
    """近重复名的规范形：抹掉「实验：」「探究」等前后缀后再 norm。

    实测需要它：同一件事被不同代理写成「验证机械能守恒定律实验」与「实验：验证机械能守恒定律」，
    不归并就会建出两个同义节点。
    """
    t = re.sub(r"^(实验[：: ]*|探究|测量)", "", name.strip())
    t = re.sub(r"(实验|探究|的测定|的测量)$", "", t)
    return norm(t)


def main(argv: list[str] | None = None) -> int:
    argparse.ArgumentParser(description=__doc__).parse_args(argv)
    pack = pack_io.load_json(pack_io.pack_path())
    topics = {s["subject"]: {t["slug"] for t in s["topics"]} for s in pack["subjects"]}
    name_by: dict[str, dict[str, str]] = {}
    for s in pack["subjects"]:
        for t in s["topics"]:
            for kp in t.get("knowledgePoints") or []:
                name_by.setdefault(s["subject"], {}).setdefault(norm(kp["name"]), kp["slug"])
                name_by[s["subject"]].setdefault(norm(kp["slug"]), kp["slug"])

    new = json.loads(NEW_JSON.read_text(encoding="utf-8"))["new"]
    evidence: dict[str, dict] = {}
    with MERGED.open(encoding="utf-8") as f:
        for r in csv.DictReader(f):
            if r["node_slug"].startswith("NEW:"):
                evidence.setdefault(r["node_slug"][4:], r)

    def subject_of(rel: str) -> str | None:
        for key, subj in (("化学", "CHEMISTRY"), ("生物", "BIOLOGY"), ("物理", "PHYSICS"), ("数学", "MATH")):
            if key in rel:
                return subj
        return None

    plan, rebind, bad = [], {}, []
    # 新节点之间的近重复归并：同科内 canonical 相同的一组，取最短名为代表，其余指向代表
    groups: dict[tuple[str, str], list[str]] = {}
    for name in new:
        ev = evidence.get(name)
        subj = subject_of(ev["chunk_rel"]) if ev else ""
        groups.setdefault((subj, canonical(name)), []).append(name)
    alias: dict[str, str] = {}
    for names in groups.values():
        if len(names) > 1:
            rep = min(names, key=lambda n: (len(n), n))
            for n in names:
                if n != rep:
                    alias[n] = rep
    for name in new:
        if name in alias:
            continue                      # 别名行在主循环里跟随代表处理
        ev = evidence.get(name)
        if ev is None:
            bad.append((name, "无证据行"))
            continue
        subj = subject_of(ev["chunk_rel"])
        if subj is None:
            bad.append((name, "学科未识别"))
            continue
        # 该组的全部写法（含代表自己）都要改绑到同一目标
        members = [n for n in [name] + [k for k, v in alias.items() if v == name]]
        existing = name_by.get(subj, {}).get(norm(name))
        if existing:
            rebind.update({m: existing for m in members})
            continue
        # 包含匹配兜底：既有节点名与候选名互为包含且长度差很小（如「…反应在不同的介质中…」vs
        # 「…反应式在不同的介质中…」）——同物，改绑而非新建。
        cand = norm(name)
        near = [slug for key, slug in name_by.get(subj, {}).items()
                if abs(len(key) - len(cand)) <= 4 and (key in cand or cand in key)]
        if len(set(near)) == 1:
            rebind.update({m: near[0] for m in members})
            continue
        verdict = gate._is_bad_name(name)
        if verdict:
            bad.append((name, f"坏名：{verdict}"))
            continue
        if len(name) > 24:
            bad.append((name, f"超长 {len(name)}"))
            continue
        # 组内别名指向代表（代表会被建点，slug 就是代表名）
        rebind.update({m: name for m in members if m != name})
        target = None
        for pat, slug in rules().get(subj, []):
            # 目标至少要两段（册·章…）：单段顶层主题推不出"册 章"定位串，
            # 点挂上去等于挂在册层——上一轮实测会被 chapter_no_book 抓住。
            if len(slug.split("·")) < 2:
                continue
            if re.search(pat, name) and slug in topics[subj]:
                target = slug
                break
        if target is None:
            target = fallback(subj, topics[subj])
        seg = target.split("·")
        place = f"{subj}·综合·综合" if target.endswith("综合·综合·综合") else f"{seg[0]} {seg[1]}"
        plan.append({
            "subject": subj, "slug": name, "name": name,
            "kind": "REASONING" if re.search(r"辨析|易错|陷阱|注意", name) else "CONCEPT",
            "parent_topic_slug": target,
            "boundary": f"定位：{place}。{re.sub(r'[\s]+', ' ', (ev['boundary'] or ev['summary'] or '').strip())[:120].replace(',', '，').replace('\"', '”')}",
            "source_locator": "《2026/2027 一轮复习讲义·学生版 / 知识清单·学生版》文本判定",
            "place": place,
        })
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"plan": plan, "rebind": rebind, "bad": bad},
                              ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"NEW 名 {len(new)}：可建点 {len(plan)}、可改绑既有节点 {len(rebind)}、需人工处置 {len(bad)}")
    print("按科：", Counter(p["subject"] for p in plan).most_common())
    print("归属分布（前 12）：")
    for target, count in Counter(p["parent_topic_slug"] for p in plan).most_common(12):
        print(f"   {count:>4}  {target[:62]}")
    print("需人工处置：")
    for name, why in bad[:12]:
        print(f"   ! {name[:34]} — {why}")
    print("可改绑样例：", list(rebind.items())[:6])
    print(f"→ {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
