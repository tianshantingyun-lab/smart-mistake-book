# -*- coding: utf-8 -*-
"""本轮渲染驱动：解题觉醒大招册四科 + 要点觉醒 + 考前急救图。
每科一个输出目录 build/render/jjdx-<subj>-dazhao/。4 进程并行。
"""
import sys, pathlib, multiprocessing as mp
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from render_pages import render  # noqa: E402

BASE = pathlib.Path(r"C:\Users\听云\Desktop\知识库原始数据资料")
OUT = pathlib.Path(r"D:\smart mistake book\build\render")

JOBS = [
    (r"02-【数学】2027版高中《解题觉醒》\数学解题觉醒-第2册 名师大招册.pdf", "jjdx-math-dazhao"),
    (r"02-【数学】2027版高中《解题觉醒》\考前急救图.pdf", "jjdx-math-jijiu"),
    (r"04-【物理】2027版高中《解题觉醒》\物理解题觉醒-第2册 名师大招册.pdf", "jjdx-phy-dazhao"),
    (r"04-【物理】2027版高中《解题觉醒》\物理要点觉醒.pdf", "jjdx-phy-yaodian"),
    (r"05-【化学】2027版高中《解题觉醒》\化学解题觉醒-第2册 名师大招册.pdf", "jjdx-chem-dazhao"),
    (r"05-【化学】2027版高中《解题觉醒》\化学要点觉醒图示.pdf", "jjdx-chem-yaodian"),
    (r"06-【生物】2027版高中《解题觉醒》\生物解题觉醒-第2册 名师大招册.pdf", "jjdx-bio-dazhao"),
    (r"06-【生物】2027版高中《解题觉醒》\生物要点觉醒.pdf", "jjdx-bio-yaodian"),
]


def _run(job):
    rel, out = job
    pdf = BASE / rel
    if not pdf.exists():
        return f"MISSING {pdf}"
    m = render(pdf, OUT / out, dpi=150)
    return f"{out}: total={m['total_pages']} now={m['rendered_now']} skip={m['skipped_existing']}"


if __name__ == "__main__":
    with mp.Pool(4) as pool:
        for line in pool.imap_unordered(_run, JOBS):
            print(line, flush=True)
