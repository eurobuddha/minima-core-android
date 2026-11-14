#!/usr/bin/env python3
"""Assemble Pandamonium's Dexicon dictionaries: reused-<lang>.json (Pandamonium's own approved translations) plus the
out-*-<lang>.json translation batches, validated, into app/src/pandamonium/assets/dexicon-l10n/<lang>.json.
A translation that breaks a placeholder, a ticker / product name, a number, a hex value, the line breaks or the edge
spaces is dropped (that text stays English) and reported. Keys not in english.json are dropped."""
import json, re, sys
from pathlib import Path
B = Path(__file__).resolve().parents[1]
D = B / 'integrated/dexicon-l10n'
OUT = B / 'app/src/pandamonium/assets/dexicon-l10n'
KEEP = ['MINIMA', 'MxUSD', 'USDT', 'ETH', 'Dexicon', 'AtomiX', 'PandaPools', 'PandaDEX', 'Pandamonium', 'MegaMMR', 'HTLC']

def problems(en, tr):
    p = []
    if sorted(re.findall(r'\{\d+\}', en)) != sorted(re.findall(r'\{\d+\}', tr)): p.append('placeholders')
    for w in KEEP:
        if re.search(r'(?<![A-Za-z])' + re.escape(w) + r'(?![A-Za-z])', en) and w not in tr: p.append('name ' + w)
    if en.count('\n') != tr.count('\n'): p.append('line breaks')
    if (en[:1].isspace(), en[-1:].isspace()) != (tr[:1].isspace(), tr[-1:].isspace()): p.append('edge spaces')
    if sorted(re.findall(r'0x[0-9A-Fa-f]+', en)) != sorted(re.findall(r'0x[0-9A-Fa-f]+', tr)): p.append('hex')
    for n in set(re.findall(r'(?<![\w{])\d+(?:\.\d+)?(?![\w}])', en)):
        if n not in tr: p.append('number ' + n); break
    return p

english = set(json.loads((D / 'english.json').read_text()))
OUT.mkdir(parents=True, exist_ok=True)
bad_total = 0
for lang in ['zh-Hans', 'ru', 'uk']:
    merged = {}
    sources = [D / f'reused-{lang}.json'] + sorted(D.glob(f'out-*-{lang}.json'))
    for f in sources:
        for en, tr in json.loads(f.read_text()).items():
            if en not in english or not isinstance(tr, str) or not tr.strip(): continue
            bad = problems(en, tr)
            if bad:
                bad_total += 1
                print(f'{lang}: dropped ({", ".join(bad)}): {en[:70]!r}', file=sys.stderr)
                continue
            merged.setdefault(en, tr)
    (OUT / f'{lang}.json').write_text(json.dumps(merged, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n')
    print(f'{lang}: {len(merged)} of {len(english)} texts')
print('dropped:', bad_total)
