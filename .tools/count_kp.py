
import sys, io
sys.path.insert(0, '.tools/.pyenv')
from pypdf import PdfReader
import re
for subj in ['physics','chemistry','biology']:
    r = PdfReader('.artifacts/research/high-school-standards-2025/'+subj+'.pdf')
    txt = '
'.join((p.extract_text() or '') for p in r.pages)
    # 找到 "内容要求" 之后的区域,提取所有形如 (1)(2)... 或 1. 2. 的条目
    # 课标里的细化条目通常用 (1)(2)(3) 编号
    parenthesized = re.findall(r'[（(]s*d+s*[)）][^（(]*', txt)
    numbered = re.findall(r'(?m)^s*(d+)[、.]s*(S{2,40})', txt)
    print(f'== {subj} ==')
    print(f'  总字符: {len(txt)}')
    print(f'  括号编号条目 (\\(1\\) 样式): {len(parenthesized)}')
    # 更精确: 匹配 "内容要求" 区块中的编号
    # 找所有带 ①②③ 的条目(课标常用圆序号)
    circled = re.findall(r'[①②③④⑤⑥⑦⑧⑨⑩]', txt)
    print(f'  圆圈序号总数: {len(circled)}')
