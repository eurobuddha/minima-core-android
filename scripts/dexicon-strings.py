#!/usr/bin/env python3
"""Extract Dexicon's user-facing English texts from the integrated snapshot (what Pandamonium ships).

A string literal, or a chain of literals joined with + around other expressions, becomes a template:
    "Cancel order " + o.orderId + "?"   ->   "Cancel order {0}?"
String.format placeholders (%s, %d, %.2f, %1$s) also become {n}. Output: a sorted JSON list on stdout.
Run:  python3 scripts/dexicon-strings.py > integrated/dexicon-l10n/english.json
"""
import json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / 'integrated/dexicon/src/main/java'
FORMAT = re.compile(r'%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxX]')


def tokens(src):
    """(kind, value) with kind in STR / PLUS / OPEN / CLOSE / SEP / OTHER; comments and chars skipped."""
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith('//', i):
            i = src.find('\n', i); i = n if i < 0 else i
        elif src.startswith('/*', i):
            i = src.find('*/', i + 2); i = n if i < 0 else i + 2
        elif src.startswith('"""', i):                     # text block: never UI here
            j = src.find('"""', i + 3); i = n if j < 0 else j + 3
            yield ('OTHER', '')
        elif c == '"':
            j, out = i + 1, []
            while j < n and src[j] != '"':
                if src[j] == '\\' and j + 1 < n:
                    out.append({'n': '\n', 't': '\t', '"': '"', "'": "'", '\\': '\\'}.get(src[j + 1], src[j + 1])); j += 2
                else:
                    out.append(src[j]); j += 1
            yield ('STR', ''.join(out)); i = j + 1
        elif c == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == '\\' else 1
            yield ('OTHER', ''); i = j + 1
        elif c == '+' and not src.startswith('++', i) and not src.startswith('+=', i):
            yield ('PLUS', '+'); i += 1
        elif c in '([{':
            yield ('OPEN', c); i += 1
        elif c in ')]}':
            yield ('CLOSE', c); i += 1
        elif c in ',;?:':
            yield ('SEP', c); i += 1
        elif c.isspace():
            i += 1
        else:
            j = i
            while j < n and not src[j].isspace() and src[j] not in '"\'+()[]{},;?:' and not src.startswith('//', j) and not src.startswith('/*', j):
                j += 1
            yield ('OTHER', src[i:j] if j > i else src[i]); i = max(j, i + 1)


def chains(src):
    """Templates from literal chains at one paren depth: literal (+ expr)* (+ literal)..."""
    toks = list(tokens(src))
    out, i = [], 0
    while i < len(toks):
        if toks[i][0] != 'STR':
            i += 1; continue
        parts, slot, j = [toks[i][1]], 0, i + 1
        while j < len(toks) and toks[j][0] == 'PLUS':
            j += 1
            if j < len(toks) and toks[j][0] == 'STR':
                parts.append(toks[j][1]); j += 1; continue
            # a non-literal operand: skip to the next + / separator at depth 0
            depth, start = 0, j
            while j < len(toks):
                k, v = toks[j]
                if k == 'OPEN': depth += 1
                elif k == 'CLOSE':
                    if depth == 0: break
                    depth -= 1
                elif depth == 0 and (k == 'PLUS' or k == 'SEP'): break
                j += 1
            if j == start: break
            parts.append('{%d}' % slot); slot += 1
        out.append(''.join(parts)); i = j
    return out


def ui_like(t):
    letters = re.sub(r'\{\d+\}', '', t)
    if not re.search(r'[A-Za-z]{2}', letters): return False
    s = letters.strip()
    if not s: return False
    if re.fullmatch(r'[a-z0-9_.\-/:]+', s): return False                 # keys, ids, paths, commands
    if re.match(r'^(txn\w+|coins|send|tokens|balance|keys|newscript|seedrandom|getaddress|scripts|maxima|status|block|mds|history|consolidate|hash|sign|verify|runscript|coinexport|coinimport|megammr\w*)\b', s): return False
    if re.match(r'^(SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|PRAGMA)\b', s): return False
    if s.startswith(('http://', 'https://', 'com.', 'android.', 'org.', 'java.', '0x', 'Mx')) and ' ' not in s: return False
    if re.fullmatch(r'[A-Z0-9_]+', s) and len(s) > 1: return False       # tickers / constants (MINIMA, USDT)
    if re.fullmatch(r'[A-Za-z]+\d*', s) and not s[0].isupper(): return False
    if ' ' not in s and re.search(r'[a-z][A-Z]', s): return False         # camelCase identifiers (HmacSHA256, fooBar)
    if re.search(r'\\[()dswb]|^\^|\$\)?$|\[0-9A-Fa-f\]', t): return False      # regular expressions
    if re.fullmatch(r'[\w.\-]+\.(java|json|db|txt|csv|zip|png|xml)', s): return False  # file names
    return True


def main():
    found = set()
    for p in sorted(ROOT.rglob('*.java')):
        if '/minimaapi/' in p.as_posix(): continue
        for t in chains(p.read_text()):
            n = [0]
            def fmt(_m):
                v = '{%d}' % (100 + n[0]); n[0] += 1; return v
            t = FORMAT.sub(fmt, t) if '%' in t else t
            # renumber placeholders in order of appearance
            order = []
            for m in re.finditer(r'\{(\d+)\}', t):
                if m.group(1) not in order: order.append(m.group(1))
            for new, old in enumerate(order): t = t.replace('{%s}' % old, '{_%d}' % new)
            t = t.replace('{_', '{')
            if ui_like(t) and len(t) <= 600: found.add(t)
    json.dump(sorted(found), sys.stdout, ensure_ascii=False, indent=0)
    print()


if __name__ == '__main__':
    main()
