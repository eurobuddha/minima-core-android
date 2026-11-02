"""Opt-in bounded recovery layered after the existing collection and language adaptations.

Strict context patches preserve the existing standalone source and refuse importer drift.
"""
import json
from pathlib import Path

def adapt(root):
    patches = json.loads(Path(__file__).with_name('pandamonium-sources').joinpath('pandapools/bounded-recovery.json').read_text())
    for patch in patches:
        path = root / patch['path']
        source = path.read_text()
        if source.count(patch['before']) != 1:
            raise AssertionError('Bounded recovery source drift: ' + str(path))
        path.write_text(source.replace(patch['before'], patch['after'], 1))
