#!/usr/bin/env python3
"""Seed Dexicon's per-language dictionaries from Pandamonium's existing translations (exact English matches).
Reads integrated/dexicon-l10n/english.json, writes integrated/dexicon-l10n/reused-<lang>.json."""
import json, re, html
from pathlib import Path
B = Path(__file__).resolve().parents[1]
L = B / 'localization/src/main/res'
DIRS = {'zh-Hans': 'values-b+zh+Hans', 'ru': 'values-ru', 'uk': 'values-uk'}

def load(d):
    s = (L / d / 'bear_strings.xml').read_text()
    out = {}
    for m in re.finditer(r'<string name="(l10n_\w+)"[^>]*>(.*?)</string>', s, re.S):
        v = m.group(2).strip()
        if len(v) >= 2 and v[0] == '"' and v[-1] == '"': v = v[1:-1]
        v = v.replace("\\'", "'").replace('\\"', '"').replace('\\n', '\n').replace('\\@', '@').replace('\\?', '?')
        out[m.group(1)] = html.unescape(v)
    return out

def norm(v):
    i = [0]
    def f(m):
        if m.group(1): return '{%d}' % (int(m.group(1)[:-1]) - 1)
        r = '{%d}' % i[0]; i[0] += 1; return r
    return re.sub(r'%(\d+\$)?[sd]', f, v)

en = load('values')
want = set(json.loads((B / 'integrated/dexicon-l10n/english.json').read_text()))
for code, d in DIRS.items():
    tr = load(d); got = {}
    for k, v in en.items():
        t = norm(v)
        if t in want and k in tr and tr[k] and t not in got: got[t] = norm(tr[k])
    (B / f'integrated/dexicon-l10n/reused-{code}.json').write_text(json.dumps(got, ensure_ascii=False, indent=0, sort_keys=True) + '\n')
    print(code, len(got), 'reused of', len(want))
