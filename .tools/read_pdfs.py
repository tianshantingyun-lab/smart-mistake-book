
import sys
sys.path.insert(0, '.tools/.pyenv')
from pypdf import PdfReader
import re
for subj in ['physics','chemistry','biology']:
    f = '.artifacts/research/high-school-standards-2025/'+subj+'.pdf'
    r = PdfReader(f)
    txt = '\n'.join((p.extract_text() or '') for p in r.pages)
    print('== '+subj+' ==  pages:', len(r.pages), ' chars:', len(txt))
    # 统计"内容要求"和"学业要求"次数
    print('   内容要求 count:', txt.count('内容要求'))
    print('   学业要求 count:', txt.count('学业要求'))
    # 找出所有带编号的行(细分条目的近似)
    num_lines = [l.strip() for l in txt.split('\n') if re.match(r'^(\d+\.|（\d+）|\(\d+\))', l.strip())]
    print('   编号条目行数(近似):', len(num_lines))
