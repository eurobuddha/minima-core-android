"""Native archive proof recovery layered after bounded recovery. No node reset."""
import json
from pathlib import Path

def adapt(root):
    sources = Path(__file__).with_name('pandamonium-sources') / 'pandapools'
    for patch in json.loads((sources / 'archive-recovery.json').read_text()):
        path = root / Path(patch['path']).relative_to('integrated')
        source = path.read_text()
        if source.count(patch['before']) != 1:
            raise AssertionError('Archive recovery source drift: ' + str(path))
        path.write_text(source.replace(patch['before'], patch['after'], 1))
    for name in ['MegaArchive.java', 'MegaArchiveProofs.java']:
        (root / 'pandapools/src/main/java/com/eurobuddha/pandapools' / name).write_text((sources / name).read_text())
