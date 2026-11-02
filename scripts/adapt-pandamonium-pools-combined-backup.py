"""Password-encrypted combined backup, applied after archive recovery."""
import json
from pathlib import Path
def adapt(root):
    sources=Path(__file__).with_name('pandamonium-sources')/'pandapools'
    for patch in json.loads((sources/'combined-backup.json').read_text()):
        p=root/Path(patch['path']).relative_to('integrated');s=p.read_text()
        if s.count(patch['before'])!=1:raise AssertionError('Combined backup source drift: '+str(p))
        p.write_text(s.replace(patch['before'],patch['after'],1))
    for name in ['BackupVault.java','PoolBackupBundle.java','NodeBackupCapture.java','CombinedBackupUi.java']:
        (root/'pandapools/src/main/java/com/eurobuddha/pandapools'/name).write_text((sources/name).read_text())
