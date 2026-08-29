
import sys, io
sys.path.insert(0, '.tools/.pyenv')
from pypdf import PdfReader
r = PdfReader('.artifacts/research/high-school-standards-2025/physics.pdf')
txt = '\n'.join((p.extract_text() or '') for p in r.pages)
open('.tools/physics_text.txt','w',encoding='utf-8').write(txt)
print('written', len(txt))
